#!/bin/bash
work_path=$(dirname $0)
cd ${work_path}
cd ..

echo airopscat is starting

nohup java -server -jar ./airopscat.jar > /dev/null 2>&1 &