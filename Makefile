############################################################-*-Makefile-*-####
# Makefile for the Scala Compiler
##############################################################################
# $Id$

##############################################################################
# Configuration

ROOT			 = .

include $(ROOT)/Makefile.import

##############################################################################
# Variables

# project sources
PROJECT_SOURCES		+= $(LAMPLIB_SOURCES)
PROJECT_SOURCES		+= $(META_SOURCES)
PROJECT_SOURCES		+= $(COMPILER_SOURCES)
PROJECT_SOURCES		+= $(LIBRARY_SOURCES)
PROJECT_SOURCES		+= $(INTERPRETER_SOURCES)
PROJECT_SOURCES		+= $(SCALADOC_SOURCES)
PROJECT_SOURCES		+= $(DTD2SCALA_SOURCES)
PROJECT_SOURCES		+= $(SCALAP_SOURCES)
PROJECT_SOURCES		+= $(SCALATEST_SOURCES)

# scala scripts wrapper
SCRIPTS_PREFIX		 = $(PROJECT_BINARYDIR)
SCRIPTS_WRAPPER		 = $(SCRIPTS_PREFIX)/.scala_wrapper
SCRIPTS_WRAPPER_LINKS	+= $(SCRIPTS_WRAPPER_ALIASES:%=$(SCRIPTS_PREFIX)/%)
SCRIPTS_WRAPPER_ALIASES	+= scala
SCRIPTS_WRAPPER_ALIASES	+= scala-info
SCRIPTS_WRAPPER_ALIASES	+= scalac
SCRIPTS_WRAPPER_ALIASES	+= scalac-debug
SCRIPTS_WRAPPER_ALIASES	+= scaladoc
SCRIPTS_WRAPPER_ALIASES	+= scaladoc-debug
SCRIPTS_WRAPPER_ALIASES	+= scalarun
SCRIPTS_WRAPPER_ALIASES	+= scalarun-debug
SCRIPTS_WRAPPER_ALIASES	+= scalaint
SCRIPTS_WRAPPER_ALIASES	+= scalaint-debug
SCRIPTS_WRAPPER_ALIASES	+= socos
SCRIPTS_WRAPPER_ALIASES	+= socos-debug
SCRIPTS_WRAPPER_ALIASES	+= siris
SCRIPTS_WRAPPER_ALIASES	+= siris-debug
SCRIPTS_WRAPPER_ALIASES	+= surus
SCRIPTS_WRAPPER_ALIASES	+= surus-debug
SCRIPTS_WRAPPER_ALIASES	+= dtd2scala
SCRIPTS_WRAPPER_ALIASES += scalap
SCRIPTS_WRAPPER_ALIASES += scalatest
SCRIPTS_WRAPPER_MACRO	 = -es@{\#$(1)\#}@'"$(MACRO_$(1):$(INSTALL_PREFIX)/%=$$PREFIX/%)"'@g

# automatic generation of Function<n>.java and Tuple<n>.scala files
FUNCTION_PREFIX		 = $(LIBRARY_ROOT)
FUNCTION_FILES		+= $(filter $(FUNCTION_PREFIX)/Function%.java,$(LIBRARY_SOURCES))
FUNCTION_TEMPLATE	 = $(FUNCTION_PREFIX)/Function.java.tmpl

TUPLE_PREFIX		 = $(LIBRARY_ROOT)
TUPLE_FILES		+= $(filter $(TUPLE_PREFIX)/Tuple%.scala,$(LIBRARY_SOURCES))
TUPLE_TEMPLATE		 = $(TUPLE_PREFIX)/Tuple.scala.tmpl

# lamp library
LAMPLIB_ROOT		 = $(PROJECT_SOURCEDIR)/ch/epfl/lamp
LAMPLIB_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/lamplib.lst)
LAMPLIB_SOURCES		+= $(LAMPLIB_LIST:%=$(LAMPLIB_ROOT)/%)
LAMPLIB_JC_FILES	+= $(LAMPLIB_SOURCES)

# meta programming
META_ROOT		 = $(PROJECT_SOURCEDIR)/meta
META_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/meta.lst)
META_SOURCES		+= $(META_LIST:%=$(META_ROOT)/%)
META_JC_FILES		+= $(META_SOURCES)

# scala compiler
COMPILER_ROOT		 = $(PROJECT_SOURCEDIR)/scalac
COMPILER_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/compiler.lst)
COMPILER_SOURCES	+= $(COMPILER_LIST:%=$(COMPILER_ROOT)/%)
COMPILER_JC_FILES	 = $(filter %.java,$(COMPILER_SOURCES))
COMPILER_JC_CLASSPATH	 = $(PROJECT_CLASSPATH):$(MSIL_JARFILE):$(FJBG_JARFILE)
COMPILER_SC_FILES	 = $(filter %.scala,$(COMPILER_SOURCES))
COMPILER_SC_CLASSPATH	 = $(COMPILER_JC_CLASSPATH)
COMPILER_SCALAC		 = $(PROJECT_BOOTSTRAPDIR)/bin/scalac

# scala library
LIBRARY_ROOT		 = $(PROJECT_SOURCEDIR)/scala
LIBRARY_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/library.lst)
LIBRARY_SOURCES		+= $(LIBRARY_LIST:%=$(LIBRARY_ROOT)/%)
LIBRARY_JC_FILES	+= $(filter %.java,$(LIBRARY_SOURCES))
LIBRARY_SC_FILES	+= $(filter %.scala,$(LIBRARY_SOURCES))
LIBRARY_SDC_FLAGS	+= -windowtitle "Scala Library Documentation"
LIBRARY_SDC_FLAGS	+= -doctitle "Scala<br/>$(PROJECT_VERSION)"
LIBRARY_SDC_FILES	+= $(LIBRARY_SC_FILES)
LIBRARY_SDC_OUTPUTDIR	 = $(PROJECT_APIDOCDIR)
LIBRARY_JAR_ARCHIVE	 = $(PROJECT_LIBRARYDIR)/$(PROJECT_NAME).jar
LIBRARY_JAR_INPUTDIR	 = $(PROJECT_OUTPUTDIR)
LIBRARY_JAR_FILES	+= scala

# scala interpreter
INTERPRETER_ROOT	 = $(PROJECT_SOURCEDIR)/scala/tools/scalai
INTERPRETER_LIST	 = $(call READLIST,$(PROJECT_LISTDIR)/interpreter.lst)
INTERPRETER_SOURCES	+= $(INTERPRETER_LIST:%=$(INTERPRETER_ROOT)/%)
INTERPRETER_JC_FILES	 = $(INTERPRETER_SOURCES)

# scaladoc
SCALADOC_ROOT		 = $(PROJECT_SOURCEDIR)/scala/tools/scaladoc
SCALADOC_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/scaladoc.lst)
SCALADOC_SOURCES	+= $(SCALADOC_LIST:%=$(SCALADOC_ROOT)/%)
SCALADOC_JC_FILES	 = $(filter %.java,$(SCALADOC_SOURCES))
SCALADOC_SC_FILES	 = $(filter %.scala,$(SCALADOC_SOURCES))
SCALADOC_RSRC_LIST	 = resources/style.css resources/script.js
SCALADOC_RSRC_FILES	+= $(SCALADOC_RSRC_LIST:%=$(SCALADOC_ROOT)/%)
SCALADOC_RSRC_OUTPUTDIR = $(SCALADOC_ROOT:$(PROJECT_SOURCEDIR)/%=$(PROJECT_OUTPUTDIR)/%)

# dtd2scala
DTD2SCALA_ROOT		 = $(PROJECT_SOURCEDIR)/scala/tools/dtd2scala
DTD2SCALA_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/dtd2scala.lst)
DTD2SCALA_SOURCES	+= $(DTD2SCALA_LIST:%=$(DTD2SCALA_ROOT)/%)
DTD2SCALA_SC_FILES	 = $(filter %.scala,$(DTD2SCALA_SOURCES))
DTD2SCALA_RSRC_LIST	 = $(filter %.xml,$(DTD2SCALA_LIST))
DTD2SCALA_RSRC_FILES	 = $(filter %.xml,$(DTD2SCALA_SOURCES))
DTD2SCALA_RSRC_OUTPUTDIR = $(DTD2SCALA_ROOT:$(PROJECT_SOURCEDIR)/%=$(PROJECT_OUTPUTDIR)/%)

# scalap
SCALAP_ROOT		 = $(PROJECT_SOURCEDIR)/scala/tools/scalap
SCALAP_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/scalap.lst)
SCALAP_SOURCES		+= $(SCALAP_LIST:%=$(SCALAP_ROOT)/%)
SCALAP_SC_FILES	 	 = $(SCALAP_SOURCES)

# scalatest
SCALATEST_ROOT		 = $(PROJECT_SOURCEDIR)/scala/tools/scalatest
SCALATEST_LIST		 = $(call READLIST,$(PROJECT_LISTDIR)/scalatest.lst)
SCALATEST_SOURCES	+= $(SCALATEST_LIST:%=$(SCALATEST_ROOT)/%)
SCALATEST_JC_FILES	 = $(SCALATEST_SOURCES)

# tools archive
TOOLS_NAME		 = tools
TOOLS_JAR_ARCHIVE	 = $(PROJECT_LIBRARYDIR)/$(TOOLS_NAME).jar
TOOLS_JAR_INPUTDIR	 = $(PROJECT_OUTPUTDIR)
TOOLS_JAR_FILES		+= ch
TOOLS_JAR_FILES		+= scalac
TOOLS_JAR_FILES		+= scala/tools/scaladoc
TOOLS_JAR_FILES		+= scala/tools/scalai
TOOLS_JAR_FILES		+= scala/tools/dtd2scala
TOOLS_JAR_FILES		+= scala/tools/scalap
TOOLS_JAR_FILES		+= scala/tools/scalatest

# java compilation
JC_COMPILER		 = PICO
JC_OUTPUTDIR		 = $(PROJECT_OUTPUTDIR)
JC_CLASSPATH		 = $(PROJECT_CLASSPATH)

# scala compilation
SC_COMPILER		 = SCALAC
SC_OUTPUTDIR		 = $(PROJECT_OUTPUTDIR)
SC_CLASSPATH		 = $(PROJECT_OUTPUTDIR)

##############################################################################
# Commands

all		: sources
#all		: bootstrap
all		: system
all		: interpreter
all		: scaladoc
all		: dtd2scala
all		: scalap
all		: scalatest
all		: library-doc

force		: fastclean
	@$(make) all

fastclean	:
	@if [ -f .generated ]; then $(call RUN,$(RM) `$(CAT) .generated`); fi
	$(RM) .generated
	$(RM) .latest-scalatest-jc
	$(RM) .latest-dtd2scala-sc
	$(RM) .latest-dtd2scala-rsrc
	$(RM) .latest-scalap-sc
	$(RM) .latest-scaladoc-jc
	$(RM) .latest-scaladoc-sc
	$(RM) .latest-scaladoc-rsrc
	$(RM) .latest-interpreter-jc
	$(RM) .latest-library-sc
	$(RM) .latest-library-jc
	$(RM) .latest-compiler-sc
	$(RM) .latest-compiler-jc
	$(RM) .latest-generate
	$(RM) .latest-meta-jc
	$(RM) .latest-lamplib-jc

clean		: fastclean
	$(RM) -r $(PROJECT_OUTPUTDIR)

distclean	: clean
	$(RM) .latest-*
	$(RM) $(SCRIPTS_WRAPPER_LINKS)
	$(RM) $(SCRIPTS_WRAPPER)
	$(RM) $(LIBRARY_JAR_ARCHIVE)
	$(RM) $(TOOLS_JAR_ARCHIVE)
	$(RM) $(ROOT)/support/latex/*.class
	$(RM) -r $(PROJECT_APIDOCDIR)
	$(RM) -r $(PROJECT_BOOTSTRAPDIR)

sources		: lamplib
sources		: meta
sources		: generate

system		: scripts
system		: lamplib
system		: compiler
system		: library

bootstrap	: .latest-bootstrap
scripts		: $(SCRIPTS_WRAPPER_LINKS)
lamplib		: .latest-$(boot)lamplib-jc
meta		: .latest-meta-jc
generate	: .latest-generate
compiler	: .latest-$(boot)compiler-jc
compiler	: .latest-$(boot)compiler-sc
library		: .latest-$(boot)library-jc
library		: .latest-$(boot)library-sc
interpreter	: .latest-interpreter-jc
scaladoc	: .latest-scaladoc-jc
scaladoc	: .latest-scaladoc-sc
scaladoc	: .latest-scaladoc-rsrc
dtd2scala	: .latest-dtd2scala-sc
dtd2scala	: .latest-dtd2scala-rsrc
scalap		: .latest-scalap-sc
scalatest	: .latest-scalatest-jc
library-doc	: .latest-library-sdc
scalac4ant	:
	cd support/ant && ant

.PHONY		: sources
.PHONY		: system
.PHONY		: fastclean
.PHONY		: bootstrap
.PHONY		: scripts
.PHONY		: lamplib
.PHONY		: meta
.PHONY		: generate
.PHONY		: compiler
.PHONY		: library
.PHONY		: interpreter
.PHONY		: scaladoc
.PHONY		: dtd2scala
.PHONY		: scalap
.PHONY		: scalatest
.PHONY		: library-doc
.PHONY		: scala4ant

##############################################################################
# Commands - Version management

version-set		:
	@if [ -z "$(VERSION)" ]; then \
	    echo "Usage: $(MAKE) version-set VERSION=<version>"; \
	    exit 1; \
	else \
	    $(call RUN,$(VERSION_SCRIPT) $(VERSION_FILE) set $(VERSION)); \
	    $(make) scripts; \
	fi

version-update		:
	$(VERSION_SCRIPT) $(VERSION_FILE) update
	@$(make) scripts

version-increment	:
	$(VERSION_SCRIPT) $(VERSION_FILE) increment
	@$(make) scripts

.PHONY			: version-set
.PHONY			: version-update
.PHONY			: version-increment

##############################################################################
# Commands - CVS management

cvs-fix-perms		:
	$(strip \
	    $(FIND) . -type f -perm +a=x | \
	    $(GREP) -v '.*/bin/.*' | \
	    $(XARGS) -r $(CHMOD) a-x)

.PHONY			: cvs-fix-perms

##############################################################################
# Targets

.latest-bootstrap		:
	$(RM) -r $(PROJECT_BOOTSTRAPDIR)
	$(MKDIR) $(PROJECT_BOOTSTRAPDIR)
	$(MKDIR) $(PROJECT_BOOTSTRAPDIR)/bin
	$(CP) $(SCRIPTS_WRAPPER).tmpl $(PROJECT_BOOTSTRAPDIR)/bin/
	@$(make) \
	    INSTALL_PREFIX=$(PROJECT_BOOTSTRAPDIR) \
	    PROJECT_BINARYDIR=$(PROJECT_BOOTSTRAPDIR)/bin \
	    PROJECT_OUTPUTDIR=$(PROJECT_BOOTSTRAPDIR)/classes \
	    boot="bootstrap-" system
	touch $@

.latest-$(boot)lamplib-jc	: $(LAMPLIB_JC_FILES)
	@$(make) jc target=LAMPLIB LAMPLIB_JC_FILES='$?'
	touch $@

.latest-meta-jc			: $(META_JC_FILES)
	@$(make) jc target=META META_JC_FILES='$?'
	$(RM) .latest-*compiler-jc
	$(RM) .latest-*compiler-sc
	$(RM) .latest-*library-jc
	$(RM) .latest-*library-sc
	touch $@

.latest-generate		: .latest-meta-jc
	@if [ -f .generated ]; then $(call RUN,$(RM) `$(CAT) .generated`); fi
	$(strip $(JAVA) -cp $(JC_OUTPUTDIR) \
	    meta.GenerateAll $(PROJECT_SOURCEDIR) .generated)
	touch $@

.latest-$(boot)compiler-jc	: $(COMPILER_JC_FILES)
	@$(make) jc target=COMPILER COMPILER_JC_FILES='$?'
	touch $@

.latest-$(boot)compiler-sc	: $(COMPILER_SC_FILES)
	@if [ -d $(PROJECT_BOOTSTRAPDIR) -a -z "$(boot)" ]; then \
	    $(make) sc target=COMPILER COMPILER_SC_FILES='$?'; \
	fi;
	touch $@

.latest-$(boot)library-jc	: $(LIBRARY_JC_FILES)
	@$(make) jc target=LIBRARY LIBRARY_JC_FILES='$(subst $$,$$$$,$?)'
	touch $@

.latest-$(boot)library-sc	: $(LIBRARY_SC_FILES)
	@$(make) sc target=LIBRARY LIBRARY_SC_FILES='$(subst $$,$$$$,$?)'
	touch $@

.latest-library-sdc		: $(LIBRARY_SDC_FILES)
	@$(make) sdc target=LIBRARY
	touch $@

.latest-interpreter-jc		: $(INTERPRETER_JC_FILES)
	@$(make) jc target=INTERPRETER INTERPRETER_JC_FILES='$?'
	touch $@

.latest-scaladoc-jc		: $(SCALADOC_JC_FILES)
	@$(make) jc target=SCALADOC SCALADOC_JC_FILES='$?'
	touch $@

.latest-scaladoc-sc		: $(SCALADOC_SC_FILES)
	@$(make) sc target=SCALADOC SCALADOC_SC_FILES='$?'
	touch $@

.latest-scaladoc-rsrc		: $(SCALADOC_RSRC_FILES)
	$(strip $(MIRROR) -m 644 -C $(SCALADOC_ROOT) $(SCALADOC_RSRC_LIST) \
	    $(SCALADOC_RSRC_OUTPUTDIR))
	touch $@

.latest-dtd2scala-sc		: $(DTD2SCALA_SC_FILES)
	@$(make) sc target=DTD2SCALA DTD2SCALA_SC_FILES='$?'
	touch $@

.latest-dtd2scala-rsrc		: $(DTD2SCALA_RSRC_FILES)
	$(strip $(MIRROR) -m 644 -C $(DTD2SCALA_ROOT) $(DTD2SCALA_RSRC_LIST) \
	    $(DTD2SCALA_RSRC_OUTPUTDIR))
	touch $@

.latest-scalap-sc		: $(SCALAP_SC_FILES)
	@$(make) sc target=SCALAP SCALAP_SC_FILES='$?'
	touch $@

.latest-scalatest-jc		: $(SCALATEST_JC_FILES)
	@$(make) jc target=SCALATEST SCALATEST_JC_FILES='$?'
	touch $@

##############################################################################
# Rules

$(SCRIPTS_WRAPPER)	: INSTALL_PREFIX          ?= $(PROJECT_ROOT)
$(SCRIPTS_WRAPPER)	: MACRO_VERSION           ?= $(PROJECT_VERSION)
$(SCRIPTS_WRAPPER)	: MACRO_RUNTIME_SOURCES   ?= $(PROJECT_SOURCEDIR)
$(SCRIPTS_WRAPPER)	: MACRO_RUNTIME_CLASSES   ?= $(PROJECT_OUTPUTDIR)
$(SCRIPTS_WRAPPER)	: MACRO_TOOLS_CLASSES     ?= $(PROJECT_OUTPUTDIR)
$(SCRIPTS_WRAPPER)	: MACRO_FJBG_CLASSES      ?= $(FJBG_JARFILE)
$(SCRIPTS_WRAPPER)	: MACRO_MSIL_CLASSES      ?= $(MSIL_JARFILE)
$(SCRIPTS_WRAPPER)	: MACRO_JAVA_ARGS         ?= -enableassertions
$(SCRIPTS_WRAPPER)	: $(VERSION_FILE)
$(SCRIPTS_WRAPPER)	: $(PROJECT_ROOT)/Makefile
$(SCRIPTS_WRAPPER)	: $(PROJECT_ROOT)/Makefile.config
$(SCRIPTS_WRAPPER)	: $(PROJECT_ROOT)/Makefile.private
$(SCRIPTS_WRAPPER)	: $(SCRIPTS_WRAPPER).tmpl
	$(RM) $@
	@$(ECHO) "Generating file $@ $(PROJECT_ROOT)"
	@$(SED) \
	    $(call SCRIPTS_WRAPPER_MACRO,VERSION) \
	    $(call SCRIPTS_WRAPPER_MACRO,RUNTIME_SOURCES) \
	    $(call SCRIPTS_WRAPPER_MACRO,RUNTIME_CLASSES) \
	    $(call SCRIPTS_WRAPPER_MACRO,TOOLS_CLASSES) \
	    $(call SCRIPTS_WRAPPER_MACRO,FJBG_CLASSES) \
	    $(call SCRIPTS_WRAPPER_MACRO,MSIL_CLASSES) \
	    $(call SCRIPTS_WRAPPER_MACRO,JAVA_ARGS) \
	    $@.tmpl > $@
	@macros=`$(SED) -n -es'@.*{#\(.*\)#}.*@\1@p' < $@`; \
	if [ -n "$$macros" ]; then \
	    $(ECHO) "error: there are undefined macros: $$macros"; \
	    $(RM) $@; \
	    exit 1; \
	fi;
	$(CHMOD) 755 $@

$(SCRIPTS_WRAPPER_LINKS): $(SCRIPTS_WRAPPER)
	@if [ ! -h $@ ]; then \
	    $(call RUN,$(LN) -s $(notdir $(SCRIPTS_WRAPPER)) $@); \
	fi

$(FUNCTION_FILES)	: .latest-meta-jc $(FUNCTION_TEMPLATE)
	$(RM) .latest-generate
	@$(make) generate

$(TUPLE_FILES)		: .latest-meta-jc $(TUPLE_TEMPLATE)
	$(RM) .latest-generate
	@$(make) generate

%			: .latest-meta-jc %.tmpl
	$(RM) .latest-generate
	@$(make) generate

$(LIBRARY_JAR_ARCHIVE)	: .latest-library-jc
$(LIBRARY_JAR_ARCHIVE)	: .latest-library-sc
$(LIBRARY_JAR_ARCHIVE)	:
	@$(MAKE) jar target=LIBRARY

$(TOOLS_JAR_ARCHIVE)	: .latest-lamplib-jc
$(TOOLS_JAR_ARCHIVE)	: .latest-compiler-jc
$(TOOLS_JAR_ARCHIVE)	: .latest-compiler-sc
$(TOOLS_JAR_ARCHIVE)	: .latest-interpreter-jc
$(TOOLS_JAR_ARCHIVE)	: .latest-scaladoc-jc
$(TOOLS_JAR_ARCHIVE)	: .latest-scaladoc-sc
$(TOOLS_JAR_ARCHIVE)	: .latest-scaladoc-rsrc
$(TOOLS_JAR_ARCHIVE)	: .latest-dtd2scala-sc
$(TOOLS_JAR_ARCHIVE)	: .latest-dtd2scala-rsrc
$(TOOLS_JAR_ARCHIVE)	: .latest-scalap-sc
$(TOOLS_JAR_ARCHIVE)	: .latest-scalatest-jc
$(TOOLS_JAR_ARCHIVE)	:
	@$(MAKE) jar target=TOOLS

##############################################################################
# Includes

include $(PROJECT_ROOT)/Makefile.distrib
include $(PROJECT_SUPPORTDIR)/make/jc.mk
include $(PROJECT_SUPPORTDIR)/make/jar.mk
include $(PROJECT_SUPPORTDIR)/make/sc.mk
include $(PROJECT_SUPPORTDIR)/make/sdc.mk

##############################################################################
# Beta code

show-missing		:
	@$(RM) /tmp/check.tmp.log /tmp/check.mkf.log /tmp/check.lst.log
	@for filename in $(LIBRARY_SC_FILES:%='%'); do \
	  echo $$filename | $(TR) " " "\n" >> /tmp/check.tmp.log; \
	done
	@$(SORT) /tmp/check.tmp.log > /tmp/check.mkf.log
	@$(FIND) $(LIBRARY_ROOT) -name '*.scala' | $(SORT) > /tmp/check.lst.log
	@$(COMM) -1 -3 /tmp/check.mkf.log /tmp/check.lst.log
	@$(RM) /tmp/check.tmp.log /tmp/check.mkf.log /tmp/check.lst.log

.PHONY			: show-missing

##############################################################################
