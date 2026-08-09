/*
 * JNI bridge between the Kotlin transport layer and libusb.
 *
 * Android's own UsbDeviceConnection multiplexes every transfer through a single kernel handle and
 * offers no way to recover a link once the framework decides a transfer failed. libusb talks to
 * usbfs directly, so the head unit can claim the accessory interface itself, tell a timeout apart
 * from a real error and clear a stalled endpoint reliably.
 *
 * Device discovery stays on the Java side: the app receives the USB permission, opens the device
 * through UsbManager and hands the resulting file descriptor over with libusb_wrap_sys_device.
 */

#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <libusb.h>

/**
 * One opened device. The handle can be closed while a blocking transfer is still running, so the
 * teardown is refcounted: whoever drops the last reference frees the context.
 */
typedef struct {
    libusb_context *ctx;
    libusb_device_handle *handle;
    int interface_number;
    int claimed;
    int closed;
    int active;
    pthread_mutex_t lock;
} ss_device;

static ss_device *from_handle(jlong value) {
    return (ss_device *) (intptr_t) value;
}

/** Releases everything owned by [dev]; must be called with no other thread holding a reference. */
static void destroy(ss_device *dev) {
    if (dev->handle != NULL) {
        if (dev->claimed) {
            libusb_release_interface(dev->handle, dev->interface_number);
        }
        libusb_close(dev->handle);
    }
    if (dev->ctx != NULL) {
        libusb_exit(dev->ctx);
    }
    pthread_mutex_destroy(&dev->lock);
    free(dev);
}

/** Takes a reference for a transfer; returns 0 when the device is already closed. */
static int acquire(ss_device *dev) {
    pthread_mutex_lock(&dev->lock);
    int usable = !dev->closed;
    if (usable) {
        dev->active++;
    }
    pthread_mutex_unlock(&dev->lock);
    return usable;
}

static void release(ss_device *dev) {
    pthread_mutex_lock(&dev->lock);
    dev->active--;
    int destroy_now = dev->closed && dev->active == 0;
    pthread_mutex_unlock(&dev->lock);
    if (destroy_now) {
        destroy(dev);
    }
}

JNIEXPORT jlong JNICALL
Java_com_ssheadunit_transport_LibUsb_nativeOpen(JNIEnv *env, jclass clazz, jint fd, jint interface_number) {
    (void) env;
    (void) clazz;
    ss_device *dev = calloc(1, sizeof(ss_device));
    if (dev == NULL) {
        return LIBUSB_ERROR_NO_MEM;
    }
    pthread_mutex_init(&dev->lock, NULL);
    dev->interface_number = interface_number;

    /*
     * An unrooted Android app cannot scan /dev/bus/usb, so device discovery is switched off and
     * the descriptor opened by UsbManager is wrapped instead. The option has to be set before the
     * context is initialised.
     */
    int result = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (result != LIBUSB_SUCCESS) {
        pthread_mutex_destroy(&dev->lock);
        free(dev);
        return result;
    }
    result = libusb_init(&dev->ctx);
    if (result != LIBUSB_SUCCESS) {
        dev->ctx = NULL;
        pthread_mutex_destroy(&dev->lock);
        free(dev);
        return result;
    }
    result = libusb_wrap_sys_device(dev->ctx, (intptr_t) fd, &dev->handle);
    if (result != LIBUSB_SUCCESS) {
        dev->handle = NULL;
        destroy(dev);
        return result;
    }
    result = libusb_claim_interface(dev->handle, interface_number);
    if (result != LIBUSB_SUCCESS) {
        destroy(dev);
        return result;
    }
    dev->claimed = 1;
    return (jlong) (intptr_t) dev;
}

/**
 * Runs one bulk transfer. Returns the number of bytes transferred, or a negative libusb error.
 * A timeout is reported as LIBUSB_ERROR_TIMEOUT even when a partial transfer happened, in which
 * case the transferred count is lost; the caller only uses whole frames, so short reads on a
 * timeout are treated as "nothing arrived".
 */
static jint transfer(JNIEnv *env, ss_device *dev, jint endpoint, jbyteArray data, jint offset,
                     jint length, jint timeout_ms, int direction_in) {
    if (length < 0 || offset < 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    if (!acquire(dev)) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    unsigned char *buffer = malloc(length > 0 ? (size_t) length : 1u);
    if (buffer == NULL) {
        release(dev);
        return LIBUSB_ERROR_NO_MEM;
    }
    if (!direction_in && length > 0) {
        (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *) buffer);
        if ((*env)->ExceptionCheck(env)) {
            free(buffer);
            release(dev);
            return LIBUSB_ERROR_INVALID_PARAM;
        }
    }

    int transferred = 0;
    int result = libusb_bulk_transfer(dev->handle, (unsigned char) endpoint, buffer, length,
                                      &transferred, (unsigned int) timeout_ms);
    if (result == LIBUSB_SUCCESS && direction_in && transferred > 0) {
        (*env)->SetByteArrayRegion(env, data, offset, transferred, (const jbyte *) buffer);
    }
    free(buffer);
    release(dev);
    if (result != LIBUSB_SUCCESS) {
        return result;
    }
    return transferred;
}

JNIEXPORT jint JNICALL
Java_com_ssheadunit_transport_LibUsb_nativeBulkRead(JNIEnv *env, jclass clazz, jlong handle,
                                                    jint endpoint, jbyteArray data, jint offset,
                                                    jint length, jint timeout_ms) {
    (void) clazz;
    return transfer(env, from_handle(handle), endpoint, data, offset, length, timeout_ms, 1);
}

JNIEXPORT jint JNICALL
Java_com_ssheadunit_transport_LibUsb_nativeBulkWrite(JNIEnv *env, jclass clazz, jlong handle,
                                                     jint endpoint, jbyteArray data, jint offset,
                                                     jint length, jint timeout_ms) {
    (void) clazz;
    return transfer(env, from_handle(handle), endpoint, data, offset, length, timeout_ms, 0);
}

JNIEXPORT jint JNICALL
Java_com_ssheadunit_transport_LibUsb_nativeClearHalt(JNIEnv *env, jclass clazz, jlong handle, jint endpoint) {
    (void) env;
    (void) clazz;
    ss_device *dev = from_handle(handle);
    if (!acquire(dev)) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    int result = libusb_clear_halt(dev->handle, (unsigned char) endpoint);
    release(dev);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ssheadunit_transport_LibUsb_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ss_device *dev = from_handle(handle);
    pthread_mutex_lock(&dev->lock);
    int already_closed = dev->closed;
    dev->closed = 1;
    int destroy_now = !already_closed && dev->active == 0;
    pthread_mutex_unlock(&dev->lock);
    if (destroy_now) {
        destroy(dev);
    }
}

JNIEXPORT jstring JNICALL
Java_com_ssheadunit_transport_LibUsb_nativeErrorName(JNIEnv *env, jclass clazz, jint code) {
    (void) clazz;
    return (*env)->NewStringUTF(env, libusb_error_name(code));
}
