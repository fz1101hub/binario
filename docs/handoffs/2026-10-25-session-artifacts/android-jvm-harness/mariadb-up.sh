#!/bin/bash
# Start the local MariaDB used by the Alexa migration tests (127.0.0.1:3317, root/test), if not running.
D=/tmp/claude-0/-home-user-binario/ec2338fa-afae-5418-81d9-da7d3b962a13/scratchpad/dev/mariadb
if mysqladmin -h127.0.0.1 -P3317 -uroot -ptest ping >/dev/null 2>&1; then echo "mariadb already up"; exit 0; fi
rm -f /tmp/mdb-bin.sock $D/m.pid
nohup mariadbd --no-defaults --user=root --datadir=$D/data --port=3317 --bind-address=127.0.0.1 --socket=/tmp/mdb-bin.sock --pid-file=$D/m.pid --log-error=$D/log/err.log > /dev/null 2>&1 &
for i in $(seq 1 30); do mysqladmin --socket=/tmp/mdb-bin.sock -uroot -ptest ping >/dev/null 2>&1 && break; sleep 1; done
mysql --socket=/tmp/mdb-bin.sock -uroot -ptest -e "ALTER USER 'root'@'localhost' IDENTIFIED BY 'test'; CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY 'test'; ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY 'test'; GRANT ALL ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION; CREATE DATABASE IF NOT EXISTS binario; FLUSH PRIVILEGES;" 2>&1 | tail -2
mysqladmin -h127.0.0.1 -P3317 -uroot -ptest ping >/dev/null 2>&1 && { echo "mariadb up"; exit 0; }
echo "mariadb failed to start"; tail -5 $D/log/err.log; exit 1
