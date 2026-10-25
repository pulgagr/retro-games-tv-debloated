LOCAL_PATH := $(call my-dir)

# Our frontend. The emulator cores are separate libraries (built from their own Android.mk files)
# that the frontend loads with dlopen, since every core defines the same retro_* functions.
include $(CLEAR_VARS)
LOCAL_MODULE    := frontend
LOCAL_SRC_FILES := frontend.c
LOCAL_CFLAGS    := -O2 -Wall
LOCAL_LDLIBS    := -landroid -llog
include $(BUILD_SHARED_LIBRARY)
