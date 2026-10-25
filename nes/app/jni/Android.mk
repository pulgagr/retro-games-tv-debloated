LOCAL_PATH := $(call my-dir)

# The fceumm libretro core, compiled into the same library as our frontend.
# Paths stay relative: the folder name "games apk" has a space, which make cannot handle.
CORE_DIR := $(LOCAL_PATH)/../../../retro/third_party/fceumm/src
include $(LOCAL_PATH)/../../../retro/third_party/fceumm/Makefile.common

include $(CLEAR_VARS)
LOCAL_MODULE    := nes
LOCAL_SRC_FILES := $(SOURCES_C:$(LOCAL_PATH)/%=%) frontend.c
LOCAL_C_INCLUDES := $(CORE_DIR)/drivers/libretro/libretro-common/include
LOCAL_CFLAGS    := $(COREDEFINES) -DPSS_STYLE=1 $(INCFLAGS) -O3 -Wno-everything
LOCAL_LDLIBS    := -landroid -llog
include $(BUILD_SHARED_LIBRARY)
