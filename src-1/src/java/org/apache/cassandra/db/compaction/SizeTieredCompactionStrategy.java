/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.compaction;

import java.util.*;
import java.util.Map.Entry;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.cql3.CFPropDefs;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.utils.Pair;

public class SizeTieredCompactionStrategy extends AbstractCompactionStrategy
{
    private static final Logger logger = LoggerFactory.getLogger(SizeTieredCompactionStrategy.class);

    protected SizeTieredCompactionStrategyOptions options;
    protected volatile int estimatedRemainingTasks;

    public SizeTieredCompactionStrategy(ColumnFamilyStore cfs, Map<String, String> options)
    {
        super(cfs, options);
        this.estimatedRemainingTasks = 0;
        this.options = new SizeTieredCompactionStrategyOptions(options);
    }

    private List<SSTableReader> getNextBackgroundSSTables(final int gcBefore)
    {
        if (!isEnabled())
            return Collections.emptyList();

        // make local copies so they can't be changed out from under us mid-method
        int minThreshold = cfs.getMinimumCompactionThreshold();
        int maxThreshold = cfs.getMaximumCompactionThreshold();

        Iterable<SSTableReader> candidates = filterSuspectSSTables(cfs.getUncompactingSSTables());
        candidates = filterColdSSTables(Lists.newArrayList(candidates), options.coldReadsToOmit);

        List<List<SSTableReader>> buckets = getBuckets(createSSTableAndLengthPairs(candidates), options.bucketHigh, options.bucketLow, options.minSSTableSize);
        logger.debug("Compaction buckets are {}", buckets);
        updateEstimatedCompactionsByTasks(buckets);
        List<SSTableReader> mostInteresting = mostInterestingBucket(buckets, minThreshold, maxThreshold);
        if (!mostInteresting.isEmpty())
            return mostInteresting;

        // if there is no sstable to compact in standard way, try compacting single sstable whose droppable tombstone
        // ratio is greater than threshold.
        List<SSTableReader> sstablesWithTombstones = new ArrayList<SSTableReader>();
        for (SSTableReader sstable : candidates)
        {
            if (worthDroppingTombstones(sstable, gcBefore))
                sstablesWithTombstones.add(sstable);
        }
        if (sstablesWithTombstones.isEmpty())
            return Collections.emptyList();

        Collections.sort(sstablesWithTombstones, new SSTableReader.SizeComparator());
        return Collections.singletonList(sstablesWithTombstones.get(0));
    }

    /**
     * Removes as many cold sstables as possible while retaining at least 1-coldReadsToOmit of the total reads/sec
     * across all sstables
     * @param sstables all sstables to consider
     * @param coldReadsToOmit the proportion of total reads/sec that will be omitted (0=omit nothing, 1=omit everything)
     * @return a list of sstables with the coldest sstables excluded until the reads they represent reaches coldReadsToOmit
     */
    @VisibleForTesting
    static List<SSTableReader> filterColdSSTables(List<SSTableReader> sstables, double coldReadsToOmit)
    {
        // sort the sstables by hotness (coldest-first)
        Collections.sort(sstables, new Comparator<SSTableReader>()
        {
            public int compare(SSTableReader o1, SSTableReader o2)
            {
                int comparison = Double.compare(hotness(o1), hotness(o2));
                if (comparison != 0)
                    return comparison;

                // break ties with size on disk (mainly for system tables and cold tables)
                comparison = Long.compare(o1.bytesOnDisk(), o2.bytesOnDisk());
                if (comparison != 0)
                    return comparison;

                // if there's still a tie, use generation, which is guaranteed to be unique.  this ensures that
                // our filtering is deterministic, which can be useful when debugging.
                return o1.descriptor.generation - o2.descriptor.generation;
            }
        });

        // calculate the total reads/sec across all sstables
        double totalReads = 0.0;
        for (SSTableReader sstr : sstables)
            if (sstr.readMeter != null)
                totalReads += sstr.readMeter.twoHourRate();

        // if this is a system table with no read meters or we don't have any read rates yet, just return them all
        if (totalReads == 0.0)
            return sstables;

        // iteratively ignore the coldest sstables until ignoring one more would put us over the coldReadsToOmit threshold
        double maxColdReads = coldReadsToOmit * totalReads;

        double totalColdReads = 0.0;
        int cutoffIndex = 0;
        while (cutoffIndex < sstables.size())
        {
            double reads = sstables.get(cutoffIndex).readMeter.twoHourRate();
            if (totalColdReads + reads > maxColdReads)
                break;

            totalColdReads += reads;
            cutoffIndex++;
        }

        return sstables.subList(cutoffIndex, sstables.size());
    }

    /**
     * @param buckets list of buckets from which to return the most interesting, where "interesting" is the total hotness for reads
     * @param minThreshold minimum number of sstables in a bucket to qualify as interesting
     * @param maxThreshold maximum number of sstables to compact at once (the returned bucket will be trimmed down to this)
     * @return a bucket (list) of sstables to compact
     */
    public static List<SSTableReader> mostInterestingBucket(List<List<SSTableReader>> buckets, int minThreshold, int maxThreshold)
    {
        // skip buckets containing less than minThreshold sstables, and limit other buckets to maxThreshold sstables
        final List<Pair<List<SSTableReader>, Double>> prunedBucketsAndHotness = new ArrayList<>(buckets.size());
        for (List<SSTableReader> bucket : buckets)
        {
            Pair<List<SSTableReader>, Double> bucketAndHotness = trimToThresholdWithHotness(bucket, maxThreshold);
            if (bucketAndHotness != null && bucketAndHotness.left.size() >= minThreshold)
                prunedBucketsAndHotness.add(bucketAndHotness);
        }
        if (prunedBucketsAndHotness.isEmpty())
            return Collections.emptyList();

        // prefer compacting the hottest bucket
        Pair<List<SSTableReader>, Double> hottest = Collections.max(prunedBucketsAndHotness, new Comparator<Pair<List<SSTableReader>, Double>>()
        {
            public int compare(Pair<List<SSTableReader>, Double> o1, Pair<List<SSTableReader>, Double> o2)
            {
                int comparison = Double.compare(o1.right, o2.right);
                if (comparison != 0)
                    return comparison;

                // break ties by compacting the smallest sstables first (this will probably only happen for
                // system tables and new/unread sstables)
                return Long.compare(avgSize(o1.left), avgSize(o2.left));
            }

            private long avgSize(List<SSTableReader> sstables)
            {
                long n = 0;
                for (SSTableReader sstable : sstables)
                    n += sstable.bytesOnDisk();
                return n / sstables.size();
            }
        });

        return hottest.left;
    }

    /**
     * Returns a (bucket, hotness) pair or null if there were not enough sstables in the bucket to meet minThreshold.
     * If there are more than maxThreshold sstables, the coldest sstables will be trimmed to meet the threshold.
     **/
    @VisibleForTesting
    static Pair<List<SSTableReader>, Double> trimToThresholdWithHotness(List<SSTableReader> bucket, int maxThreshold)
    {
        // sort by sstable hotness (descending)
        Collections.sort(bucket, new Comparator<SSTableReader>()
        {
            public int compare(SSTableReader o1, SSTableReader o2)
            {
                return -1 * Double.compare(hotness(o1), hotness(o2));
            }
        });

        // and then trim the coldest sstables off the end to meet the maxThreshold
        List<SSTableReader> prunedBucket = bucket.subList(0, Math.min(bucket.size(), maxThreshold));

        // bucket hotness is the sum of the hotness of all sstable members
        double bucketHotness = 0.0;
        for (SSTableReader sstr : prunedBucket)
            bucketHotness += hotness(sstr);

        return Pair.create(prunedBucket, bucketHotness);
    }

    /**
     * Returns the reads per second per key for this sstable, or 0.0 if the sstable has no read meter
     */
    private static double hotness(SSTableReader sstr)
    {
        // system tables don't have read meters, just use 0.0 for the hotness
        return sstr.readMeter == null ? 0.0 : sstr.readMeter.twoHourRate() / sstr.estimatedKeys();
    }

    public synchronized AbstractCompactionTask getNextBackgroundTask(int gcBefore)
    {
        if (!isEnabled())
            return null;

        while (true)
        {
            List<SSTableReader> hottestBucket = getNextBackgroundSSTables(gcBefore);

            if (hottestBucket.isEmpty())
                return null;

            if (cfs.getDataTracker().markCompacting(hottestBucket))
                return new CompactionTask(cfs, hottestBucket, gcBefore);
        }
    }

    public AbstractCompactionTask getMaximalTask(final int gcBefore)
    {
        Iterable<SSTableReader> sstables = cfs.markAllCompacting();
        if (sstables == null)
            return null;

        return new CompactionTask(cfs, sstables, gcBefore);
    }

    public AbstractCompactionTask getUserDefinedTask(Collection<SSTableReader> sstables, final int gcBefore)
    {
        assert !sstables.isEmpty(); // checked for by CM.submitUserDefined

        if (!cfs.getDataTracker().markCompacting(sstables))
        {
            logger.debug("Unable to mark {} for compaction; probably a background compaction got to it first.  You can disable background compactions temporarily if this is a problem", sstables);
            return null;
        }

        return new CompactionTask(cfs, sstables, gcBefore).setUserDefined(true);
    }

    public int getEstimatedRemainingTasks()
    {
        return estimatedRemainingTasks;
    }

    public static List<Pair<SSTableReader, Long>> createSSTableAndLengthPairs(Iterable<SSTableReader> sstables)
    {
        List<Pair<SSTableReader, Long>> sstableLengthPairs = new ArrayList<Pair<SSTableReader, Long>>(Iterables.size(sstables));
        for(SSTableReader sstable : sstables)
            sstableLengthPairs.add(Pair.create(sstable, sstable.onDiskLength()));
        return sstableLengthPairs;
    }

    /*
     * Group files of similar size into buckets.
     */
    public static <T> List<List<T>> getBuckets(Collection<Pair<T, Long>> files, double bucketHigh, double bucketLow, long minSSTableSize)
    {
        // Sort the list in order to get deterministic results during the grouping below
        List<Pair<T, Long>> sortedFiles = new ArrayList<Pair<T, Long>>(files);
        Collections.sort(sortedFiles, new Comparator<Pair<T, Long>>()
        {
            public int compare(Pair<T, Long> p1, Pair<T, Long> p2)
            {
                return p1.right.compareTo(p2.right);
            }
        });

        Map<Long, List<T>> buckets = new HashMap<Long, List<T>>();

        outer:
        for (Pair<T, Long> pair: sortedFiles)
        {
            long size = pair.right;

            // look for a bucket containing similar-sized files:
            // group in the same bucket if it's w/in 50% of the average for this bucket,
            // or this file and the bucket are all considered "small" (less than `minSSTableSize`)
            for (Entry<Long, List<T>> entry : buckets.entrySet())
            {
                List<T> bucket = entry.getValue();
                long oldAverageSize = entry.getKey();
                if ((size > (oldAverageSize * bucketLow) && size < (oldAverageSize * bucketHigh))
                    || (size < minSSTableSize && oldAverageSize < minSSTableSize))
                {
                    // remove and re-add under new new average size
                    buckets.remove(oldAverageSize);
                    long totalSize = bucket.size() * oldAverageSize;
                    long newAverageSize = (totalSize + size) / (bucket.size() + 1);
                    bucket.add(pair.left);
                    buckets.put(newAverageSize, bucket);
                    continue outer;
                }
            }

            // no similar bucket found; put it in a new one
            ArrayList<T> bucket = new ArrayList<T>();
            bucket.add(pair.left);
            buckets.put(size, bucket);
        }

        return new ArrayList<List<T>>(buckets.values());
    }

    private void updateEstimatedCompactionsByTasks(List<List<SSTableReader>> tasks)
    {
        int n = 0;
        for (List<SSTableReader> bucket: tasks)
        {
            if (bucket.size() >= cfs.getMinimumCompactionThreshold())
                n += Math.ceil((double)bucket.size() / cfs.getMaximumCompactionThreshold());
        }
        estimatedRemainingTasks = n;
    }

    public long getMaxSSTableSize()
    {
        return Long.MAX_VALUE;
    }

    public static Map<String, String> validateOptions(Map<String, String> options) throws ConfigurationException
    {
        Map<String, String> uncheckedOptions = AbstractCompactionStrategy.validateOptions(options);
        uncheckedOptions = SizeTieredCompactionStrategyOptions.validateOptions(options, uncheckedOptions);

        uncheckedOptions.remove(CFPropDefs.KW_MINCOMPACTIONTHRESHOLD);
        uncheckedOptions.remove(CFPropDefs.KW_MAXCOMPACTIONTHRESHOLD);

        return uncheckedOptions;
    }

    public String toString()
    {
        return String.format("SizeTieredCompactionStrategy[%s/%s]",
            cfs.getMinimumCompactionThreshold(),
            cfs.getMaximumCompactionThreshold());
    }
}