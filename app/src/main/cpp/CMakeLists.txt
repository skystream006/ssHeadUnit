cmake_minimum_required(VERSION 3.22.1)

project(ssusb C)

include(FetchContent)

# libusb is not vendored: the pinned release tarball is downloaded (and hash checked) when the
# native build is configured. Set -DLIBUSB_URL=file:///path/to/libusb-1.0.27.tar.bz2 to build
# from a local copy instead.
set(LIBUSB_VERSION "1.0.27")
set(LIBUSB_URL
    "https://github.com/libusb/libusb/releases/download/v${LIBUSB_VERSION}/libusb-${LIBUSB_VERSION}.tar.bz2"
    CACHE STRING "Location of the libusb source archive")
set(LIBUSB_SHA256 "ffaa41d741a8a3bee244ac8e54a72ea05bf2879663c098c82fc5757853441575")

FetchContent_Declare(
    libusb
    URL "${LIBUSB_URL}"
    URL_HASH "SHA256=${LIBUSB_SHA256}"
)
FetchContent_MakeAvailable(libusb)

# Only the POSIX/Linux backend is built; the tarball ships the Android config.h that the upstream
# ndk-build files use.
add_library(usb-1.0 STATIC
    ${libusb_SOURCE_DIR}/libusb/core.c
    ${libusb_SOURCE_DIR}/libusb/descriptor.c
    ${libusb_SOURCE_DIR}/libusb/hotplug.c
    ${libusb_SOURCE_DIR}/libusb/io.c
    ${libusb_SOURCE_DIR}/libusb/sync.c
    ${libusb_SOURCE_DIR}/libusb/strerror.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_usbfs.c
    ${libusb_SOURCE_DIR}/libusb/os/events_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/threads_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_netlink.c
)

target_include_directories(usb-1.0
    PRIVATE ${libusb_SOURCE_DIR}/android ${libusb_SOURCE_DIR}/libusb/os
    PUBLIC ${libusb_SOURCE_DIR}/libusb
)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden)
target_link_libraries(usb-1.0 PRIVATE log)

add_library(ssusb SHARED ssusb.c)
target_link_libraries(ssusb PRIVATE usb-1.0 log)
