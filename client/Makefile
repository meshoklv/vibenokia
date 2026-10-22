# VibeNokia client (J2ME MIDlet, CLDC 1.1 / MIDP 2.0)
#
#   make SERVER_URL=http://my.server.example:8085   # build via Docker (no local J2ME SDK needed)
#   make build SERVER_URL=...                       # build inside the j2me-build image / any machine with WTK
#
# Output: dist/VibeNokia.jar + dist/VibeNokia.jad. SERVER_URL is baked in as the default server address;
# it can also be changed on the phone later (Menu > Settings > Servers).

PROJECT_NAME = VibeNokia
VERSION = 2.7.0
MIDLET_CLASS = vibenokia.VibeNokiaMIDlet
MIDLET_VENDOR = VibeNokia
MIDLET_DESC = Claude terminal for feature phones
SRC_DIR = src
BUILD_DIR = build
DIST_DIR = dist
ICON_SRC = $(SRC_DIR)/vibenokia/icon.png
SERVER_URL ?= http://YOUR_SERVER:8085
PLACEHOLDER_URL = http://YOUR_SERVER:8085

# J2ME SDK paths (inside the build container)
WTK_HOME = /opt/j2me/wtk
JAVA_HOME = /opt/j2me/jdk1.8.0_202
CLASSPATH = $(WTK_HOME)/lib/midpapi20.jar:$(WTK_HOME)/lib/cldcapi11.jar:$(WTK_HOME)/lib/jsr75.jar:$(WTK_HOME)/lib/mmapi.jar

JAR_FILE = $(DIST_DIR)/$(PROJECT_NAME).jar
JAD_FILE = $(DIST_DIR)/$(PROJECT_NAME).jad
PERMS = javax.microedition.io.Connector.http, javax.microedition.io.Connector.file.read, javax.microedition.media.control.RecordControl

# Docker configuration
DOCKER_IMAGE = ghcr.io/mgruszkiewicz/docker-j2me-build:latest
HOST_ARCH := $(shell uname -m)
DOCKER_PLATFORM := $(if $(filter-out x86_64 amd64,$(HOST_ARCH)),--platform linux/amd64,)
DOCKER_RUN = docker run --rm $(DOCKER_PLATFORM) -v "$$(pwd):/workspace" $(DOCKER_IMAGE)

.PHONY: all clean build docker-build shell

all: docker-build

clean:
	rm -rf $(BUILD_DIR) $(JAR_FILE) $(JAD_FILE)

build:
	@echo "Building $(PROJECT_NAME) $(VERSION), default server: $(SERVER_URL)"
	@rm -rf $(BUILD_DIR)
	@mkdir -p $(BUILD_DIR)/src $(BUILD_DIR)/classes $(BUILD_DIR)/preverified $(BUILD_DIR)/mf $(DIST_DIR)
	@cp -r $(SRC_DIR)/vibenokia $(BUILD_DIR)/src/
	@sed -i.bak 's|$(PLACEHOLDER_URL)|$(SERVER_URL)|g' $(BUILD_DIR)/src/vibenokia/VibeNokiaMIDlet.java
	@rm -f $(BUILD_DIR)/src/vibenokia/*.bak
	$(JAVA_HOME)/bin/javac -bootclasspath $(CLASSPATH) -encoding UTF-8 -source 1.3 -target 1.3 \
		-d $(BUILD_DIR)/classes $$(ls $(BUILD_DIR)/src/vibenokia/*.java)
	$(WTK_HOME)/bin/preverify -classpath $(CLASSPATH) -d $(BUILD_DIR)/preverified $(BUILD_DIR)/classes
	@cp $(ICON_SRC) $(BUILD_DIR)/preverified/vibenokia/icon.png
	@{ echo "MIDlet-Name: $(PROJECT_NAME)"; \
	   echo "MIDlet-Version: $(VERSION)"; \
	   echo "MIDlet-Vendor: $(MIDLET_VENDOR)"; \
	   echo "MIDlet-Description: $(MIDLET_DESC)"; \
	   echo "MIDlet-Icon: /vibenokia/icon.png"; \
	   echo "MIDlet-1: $(PROJECT_NAME),/vibenokia/icon.png,$(MIDLET_CLASS)"; \
	   echo "MicroEdition-Configuration: CLDC-1.1"; \
	   echo "MicroEdition-Profile: MIDP-2.0"; \
	   echo "MIDlet-Permissions: $(PERMS)"; \
	   echo ""; } > $(BUILD_DIR)/mf/manifest.mf
	@cd $(BUILD_DIR)/preverified && $(JAVA_HOME)/bin/jar cfm ../../$(JAR_FILE) ../mf/manifest.mf vibenokia
	@{ echo "MIDlet-Name: $(PROJECT_NAME)"; \
	   echo "MIDlet-Version: $(VERSION)"; \
	   echo "MIDlet-Vendor: $(MIDLET_VENDOR)"; \
	   echo "MIDlet-Description: $(MIDLET_DESC)"; \
	   echo "MIDlet-Icon: /vibenokia/icon.png"; \
	   echo "MIDlet-1: $(PROJECT_NAME),/vibenokia/icon.png,$(MIDLET_CLASS)"; \
	   echo "MicroEdition-Configuration: CLDC-1.1"; \
	   echo "MicroEdition-Profile: MIDP-2.0"; \
	   echo "MIDlet-Jar-URL: $(PROJECT_NAME).jar"; \
	   echo "MIDlet-Jar-Size: $$(stat -c%s $(JAR_FILE) 2>/dev/null || stat -f%z $(JAR_FILE))"; \
	   echo "MIDlet-Permissions: $(PERMS)"; } > $(JAD_FILE)
	@echo "Built: $(JAR_FILE) $(JAD_FILE)"

docker-build:
	$(DOCKER_RUN) make build SERVER_URL="$(SERVER_URL)"

shell:
	docker run --rm $(DOCKER_PLATFORM) -it -v "$$(pwd):/workspace" $(DOCKER_IMAGE) bash
