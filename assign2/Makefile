# Makefile for Assignment 4

JAVAC=javac
SRC_DIR=src

# Find all .java files in src/ and subdirectories
SOURCES := $(shell find $(SRC_DIR) -name "*.java")

all: classes

classes:
	$(JAVAC) -d $(SRC_DIR) $(SOURCES)

clean:
	find $(SRC_DIR) -name "*.class" -delete
