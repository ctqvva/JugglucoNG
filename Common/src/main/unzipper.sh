#!/bin/sh
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <directory>"
    exit 1
fi
for i in "$1"/glucose_*.zip
do
	unzip "$i"
done
