# cassandra-18748
## Usage
1. Use `ant jar` to compile src-1, src-2, src-3
2. Run `./reproduction.sh` to reproduce the issue.

## Explanation
* **Background**:
  * **Fault**: As described in *https://issues.apache.org/jira/browse/CASSANDRA-18748*, the injected FileNotFoundException is in `src/java/org/apache/cassandra/io/util/MmappedSegmentedFile.java` line 178.
* **Result**: Check `logs-2/system.log` to find the corresponding logs described in *https://issues.apache.org/jira/browse/CASSANDRA-18748*, also we add timeout to the repair command and the stuck stacktrace would be printed to `stack_trace.txt`
