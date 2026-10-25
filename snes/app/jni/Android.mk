LOCAL_PATH := $(call my-dir)

# The snes9x2005 libretro core (built for slow ARM devices), compiled into the same library as our frontend.
# Paths stay relative: the folder name "games apk" has a space, which make cannot handle.
ROOT_DIR     := $(LOCAL_PATH)/../../../retro/third_party/snes9x2005
CORE_DIR     := $(ROOT_DIR)/source
LIBRETRO_DIR := $(ROOT_DIR)
LOAD_FROM_MEMORY := 1
FLAGS :=
include $(ROOT_DIR)/Makefile.common

include $(CLEAR_VARS)
LOCAL_MODULE    := snes
LOCAL_SRC_FILES := $(SOURCES_C:$(LOCAL_PATH)/%=%) frontend.c
LOCAL_CFLAGS    := $(FLAGS) $(INCFLAGS) -O3 -ffast-math -Wno-everything
LOCAL_LDLIBS    := -landroid -llog
include $(BUILD_SHARED_LIBRARY)
