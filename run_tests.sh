#!/bin/bash
echo "Compiling HTTP Server and Integration Tests..."
javac -d out src/*.java test/*.java

if [ $? -eq 0 ]; then
    echo "Compilation successful. Running tests..."
    java -cp out ServerIntegrationTest
else
    echo "Compilation failed."
    exit 1
fi
