# SimpleDBPerformanceTest
Yeat another simple performance test for Oracle, PostreSQL and MySQL. The test can run one of a number of tests against Oracle, PostgreSQL and MySQL. Currently it has been tested on Oracle 19c, 20c and PostgreSQL 12. It's a trivial test and not much about the capabilities of any of the databases can be derived from it.

The test does expect you have created a database and user to allow the application to connect to.

You can run the test one step at a time i.e. create tables, insert data, create indexes etc or run one of main task "full". On most occasions the expectation is that you'd rull a full test with a different rowcount and different numbers of users. For a "full" workload the expectation, unless you explcitly request it the results will be written to files. You can change this with the ```-o``` option.

```
usage: parameters:
 -async                  run async transactions, defaults to false
 -bs <batchsize>         batch size, defaults to 1
 -c                      run update workload
 -cf <commitfrequency>   commit frequency, defaults to 1
 -ci                     create indexes
 -create                 create tables
 -cs <connectstring>     connect string
 -debug                  turn on debugging. Written to standard out
 -di                     drop indexes
 -dr <range>             data range : the maximum value of lookups
 -full                   full workload run
 -i                      run insert workload
 -m                      run mixed workload
 -o <output>             output : valid values are stdout,csv
 -ops <operations>       operations to perform i.e. select, updates
 -p <password>           password
 -rc <rowcount>          row count, defaults to 100
 -s                      run select workload
 -sql <select_type>      which select statement to run (choices are :
                         lookup,range_scan,count)
 -st <type>              benchmark test, relational or document
 -t <db>                 target oracle or postgresql
 -tc <threadcount>       thread count, defaults to 1
 -ts                     table sizes
 -u <username>           username
 ```
 
An example of a command to run the "full" workload against PostgreSQL would be
``` 
java -jar SimpleDBTest.jar -u soe -p soe -cs //localhost/domsdb -debug -t postgresql -full -o stdout
```
You should end up withsomething like 

```
+---------------+----------------+------------+
| Test Name     | Total Time(ms) | Target     |
+---------------+----------------+------------+
| Create Tables | 2092           | POSTGRESQL |
+---------------+----------------+------------+

+----------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Test Name      | Operations | Operations/sec | Connection Time | Total Time   | Threads | Target     | Commits | Batch | Async |
+----------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Ingest via DML | 1000000    | 52935          | 00:00:00.15     | 00:00:18.891 | 1       | POSTGRESQL | 1000    | 100   | false |
| Ingest via DML | 1000000    | 172295         | 00:00:00.134    | 00:00:05.804 | 5       | POSTGRESQL | 1000    | 100   | false |
| Ingest via DML | 1000000    | 163988         | 00:00:00.496    | 00:00:06.98  | 10      | POSTGRESQL | 1000    | 100   | false |
| Ingest via DML | 1000000    | 165289         | 00:00:00.52     | 00:00:06.50  | 25      | POSTGRESQL | 1000    | 100   | false |
| Ingest via DML | 1000000    | 160591         | 00:00:00.87     | 00:00:06.227 | 50      | POSTGRESQL | 1000    | 100   | false |
+----------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+

+----------------+----------------+------------+
| Test Name      | Total Time(ms) | Target     |
+----------------+----------------+------------+
| Create Indexes | 17,665         | POSTGRESQL |
+----------------+----------------+------------+

+----------------------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Test Name                  | Operations | Operations/sec | Connection Time | Total Time   | Threads | Target     | Commits | Batch | Async |
+----------------------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Select : SIMPLE_LOOKUP     | 10000      | 12121          | 00:00:00.6      | 00:00:00.825 | 1       | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_LOOKUP     | 10000      | 25840          | 00:00:00.7      | 00:00:00.387 | 5       | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_LOOKUP     | 10000      | 49751          | 00:00:00.20     | 00:00:00.201 | 10      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_LOOKUP     | 10000      | 46948          | 00:00:00.34     | 00:00:00.213 | 25      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_LOOKUP     | 10000      | 43860          | 00:00:00.65     | 00:00:00.228 | 50      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_RANGE_SCAN | 10000      | 44             | 00:00:00.23     | 00:03:49.8   | 1       | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_RANGE_SCAN | 10000      | 109            | 00:00:00.8      | 00:01:31.678 | 5       | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_RANGE_SCAN | 10000      | 93             | 00:00:00.39     | 00:01:47.490 | 10      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_RANGE_SCAN | 10000      | 106            | 00:00:00.47     | 00:01:34.233 | 25      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_RANGE_SCAN | 10000      | 118            | 00:00:00.135    | 00:01:24.786 | 50      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_COUNT      | 10000      | 1848           | 00:00:00.180    | 00:00:05.410 | 1       | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_COUNT      | 10000      | 6345           | 00:00:00.6      | 00:00:01.576 | 5       | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_COUNT      | 10000      | 5400           | 00:00:00.18     | 00:00:01.852 | 10      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_COUNT      | 10000      | 5685           | 00:00:00.33     | 00:00:01.759 | 25      | POSTGRESQL | 1000    | 100   | false |
| Select : SIMPLE_COUNT      | 10000      | 5875           | 00:00:00.68     | 00:00:01.702 | 50      | POSTGRESQL | 1000    | 100   | false |
+----------------------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+

+--------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Test Name    | Operations | Operations/sec | Connection Time | Total Time   | Threads | Target     | Commits | Batch | Async |
+--------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Update Tests | 10000      | 6523           | 00:00:00.28     | 00:00:01.533 | 1       | POSTGRESQL | 10      | 5     | false |
| Update Tests | 10000      | 18519          | 00:00:00.163    | 00:00:00.540 | 5       | POSTGRESQL | 10      | 5     | false |
| Update Tests | 10000      | 8673           | 00:00:00.15     | 00:00:01.153 | 10      | POSTGRESQL | 10      | 5     | false |
| Update Tests | 10000      | 10040          | 00:00:00.44     | 00:00:00.996 | 25      | POSTGRESQL | 10      | 5     | false |
| Update Tests | 10000      | 10526          | 00:00:00.81     | 00:00:00.950 | 50      | POSTGRESQL | 10      | 5     | false |
+--------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+

+----------------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Test Name            | Operations | Operations/sec | Connection Time | Total Time   | Threads | Target     | Commits | Batch | Async |
+----------------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+
| Mixed Workload Tests | 10000      | 1875           | 00:00:00.33     | 00:00:05.332 | 1       | POSTGRESQL | 10      | 5     | false |
| Mixed Workload Tests | 10000      | 3867           | 00:00:00.665    | 00:00:02.586 | 5       | POSTGRESQL | 10      | 5     | false |
| Mixed Workload Tests | 10000      | 5754           | 00:00:00.139    | 00:00:01.738 | 10      | POSTGRESQL | 10      | 5     | false |
| Mixed Workload Tests | 10000      | 6906           | 00:00:00.372    | 00:00:01.448 | 25      | POSTGRESQL | 10      | 5     | false |
| Mixed Workload Tests | 10000      | 6930           | 00:00:00.474    | 00:00:01.443 | 50      | POSTGRESQL | 10      | 5     | false |
+----------------------+------------+----------------+-----------------+--------------+---------+------------+---------+-------+-------+

```

The jdbc libraries for the various databases are included in the build but I reccomend that you build from source using the ant file and keep them updated regularly.
