#include <jni.h>
#include <string>
#include <algorithm>
#define PROTOBUF_USE_DLLS 1
#define CAFFE2_USE_LITE_PROTO 1
#include <caffe2/core/predictor.h>
#include <caffe2/core/operator.h>
#include <caffe2/core/timer.h>

#include "caffe2/core/init.h"
#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/opencv.hpp>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include "classes.h"
#define IMG_H 227
#define IMG_W 227
#define IMG_C 3
#define MAX_DATA_SIZE IMG_H * IMG_W * IMG_C
#define alog(...) __android_log_print(ANDROID_LOG_ERROR, "F8DEMO", __VA_ARGS__);

static caffe2::NetDef _initNet, _predictNet;
static caffe2::Predictor *_predictor;
static float input_data[MAX_DATA_SIZE];
static caffe2::Workspace ws;

// A function to load the NetDefs from protobufs.
void loadToNetDef(AAssetManager* mgr, caffe2::NetDef* net, const char *filename) {
    AAsset* asset = AAssetManager_open(mgr, filename, AASSET_MODE_BUFFER);
    assert(asset != nullptr);
    const void *data = AAsset_getBuffer(asset);
    assert(data != nullptr);
    off_t len = AAsset_getLength(asset);
    assert(len != 0);
    if (!net->ParseFromArray(data, len)) {
        alog("Couldn't parse net from data.\n");
    }
    AAsset_close(asset);
}

extern "C"
void
Java_facebook_f8demo_ClassifyCamera_initCaffe2(
        JNIEnv* env,
        jobject /* this */,
        jobject assetManager) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    alog("Attempting to load protobuf netdefs...");
    loadToNetDef(mgr, &_initNet,   "squeeze_init_net.pb");
    loadToNetDef(mgr, &_predictNet,"squeeze_predict_net.pb");
    alog("done.");
    alog("Instantiating predictor...");
    _predictor = new caffe2::Predictor(_initNet, _predictNet);
    alog("done.")
}

float avg_fps = 0.0;
float total_fps = 0.0;
int iters_fps = 10;

extern "C"
JNIEXPORT jstring JNICALL
Java_facebook_f8demo_ClassifyCamera_classificationFromCaffe2(
        JNIEnv *env,
        jobject /* this */,
        jint h, jint w, jbyteArray jdata,
        jint rowStride, jint pixelStride,
        jboolean infer_HWC) {
    if (!_predictor) {
        return env->NewStringUTF("Loading...");
    }
    jsize data_len = env->GetArrayLength(jdata);
    jbyte * data = env->GetByteArrayElements(jdata, 0);
    assert(data_len <= MAX_DATA_SIZE);

    float b_mean = 104.00698793f/255.0;
    float g_mean = 116.66876762f/255.0;
    float r_mean = 122.67891434f/255.0;
    //alog("image size >>>> %d, %d\n", w, h);


    cv::Mat yuvMat(h + (h / 2), w, CV_8UC1, (unsigned char*)data);
    cv::Mat bgr8Mat(h, w, CV_8UC3);
    // YUV to BGR
    cv::cvtColor(yuvMat, bgr8Mat, CV_YUV2BGR_I420);

    // test write

    // BGR rescale
    cv::Mat tmp;
    cv::transpose(bgr8Mat, tmp);
    cv::resize(tmp, bgr8Mat, cv::Size(IMG_W,IMG_H)); // bilinear?

    cv::imwrite("/sdcard/Download/test1.png", bgr8Mat); //note to open permission in xml file
    
    cv::Mat bgrMat;
    bgr8Mat.convertTo(bgrMat, CV_32FC3, 1.0/255.0);
    env->ReleaseByteArrayElements(jdata, data, 0);

    // convert from [0, 255] to [0, 1]
    
    bgrMat = bgrMat - cv::Scalar(b_mean, g_mean, r_mean);

    // Copy data: not continous
    float* p = input_data;
    for(int i = 0; i < bgrMat.rows; i++){
        memcpy(p, bgrMat.ptr(i), bgrMat.cols*sizeof(float));
        p += bgrMat.cols;
    }

    caffe2::TensorCPU input;
    if (infer_HWC) {
        input.Resize(std::vector<int>({1, IMG_H, IMG_W, IMG_C}));
    } else {
        input.Resize(std::vector<int>({1, IMG_C, IMG_H, IMG_W}));
    }
    memcpy(input.mutable_data<float>(), input_data, IMG_H * IMG_W * IMG_C * sizeof(float));
    caffe2::Predictor::TensorVector input_vec{&input};
    caffe2::Predictor::TensorVector output_vec;
    caffe2::Timer t;
    t.Start();
    _predictor->run(input_vec, &output_vec);
    float fps = 1000/t.MilliSeconds();
    total_fps += fps;
    avg_fps = total_fps / iters_fps;
    total_fps -= avg_fps;

    constexpr int k = 5;
    float max[k] = {0};
    int max_index[k] = {0};
    // Find the top-k results manually.
    if (output_vec.capacity() > 0) {
        for (auto output : output_vec) {
            for (auto i = 0; i < output->size(); ++i) {
                for (auto j = 0; j < k; ++j) {
                    if (output->template data<float>()[i] > max[j]) {
                        for (auto _j = k - 1; _j > j; --_j) {
                            max[_j - 1] = max[_j];
                            max_index[_j - 1] = max_index[_j];
                        }
                        max[j] = output->template data<float>()[i];
                        max_index[j] = i;
                        goto skip;
                    }
                }
                skip:;
            }
        }
    }
    std::ostringstream stringStream;
    stringStream << avg_fps << " FPS\n";

    for (auto j = 0; j < k; ++j) {
        stringStream << j << ": " << imagenet_classes[max_index[j]] << " - " << max[j] / 10 << "%\n";
    }
    return env->NewStringUTF(stringStream.str().c_str());
}
