LIB=libnrepl-$(OS)-$(ARCH).so
CXXFLAGS=-O3 -fno-exceptions -fvisibility=hidden
INCLUDES=-I$(JAVA_HOME)/include
LIBS=-ldl
SOURCES := $(wildcard src/*.c)

ifeq ($(JAVA_HOME),)
  export JAVA_HOME:=$(shell java -cp . JavaHome)
endif

ARCH:=$(shell uname -m)
ifeq ($(ARCH),x86_64)
  ARCH=x64
else
  ifeq ($(findstring arm,$(ARCH)),arm)
    ifeq ($(findstring 64,$(ARCH)),64)
      ARCH=arm64
    else
      ARCH=arm32
    endif
  else ifeq ($(findstring aarch64,$(ARCH)),aarch64)
    ARCH=arm64
  else
    ARCH=x86
  endif
endif

OS:=$(shell uname -s)
ifeq ($(OS),Darwin)
  CXXFLAGS += -arch x86_64 -arch arm64 -mmacos-version-min=10.12
  INCLUDES += -I$(JAVA_HOME)/include/darwin
  OS=macos
  ARCH=universal
else
  CXXFLAGS += -Wl,-z,defs
  LIBS += -lrt
  INCLUDES += -I$(JAVA_HOME)/include/linux
  OS=linux
  CC=gcc
endif

all: build/$(LIB)

clean:
	$(RM) -r build

build/$(LIB): $(SOURCES)
	mkdir -p build
	$(CC) $(CXXFLAGS) $(INCLUDES) -fPIC -shared -o $@ $(SOURCES) $(LIBS)
