#!/bin/bash

PID=$(ps -ef | grep '\-jar ./airopscat.jar' | grep -v grep | awk '{ print $2 }')
if [ -z "$PID" ]
then
echo airopscat is already stopped
else
echo airopscat is stoping, kill -9 $PID
kill -9 $PID
fi

echo airopscat is starting

work_path=$(dirname $0)
cd ${work_path}
cd ..
nohup java -server -jar ./airopscat.jar > /dev/null 2>&1 &