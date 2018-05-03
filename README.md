## AICamera

AICamera is a demo app that was displayed at Facebook's F8 event.  The previous version (also on this repo) was getting quite old and attempted to demonstrate a build system that happened inside Android Studio.  This led to some hacky techniques and I decided to rewrite the demo with a prebuilt Caffe2 library (which can be built using `build_android.sh` in the Caffe2 source).

![example](https://thumbs.gfycat.com/FlimsyInbornIndianabat-size_restricted.gif)

### Download
Download opencv sdk from https://opencv.org/releases.html
e.g. https://sourceforge.net/projects/opencvlibrary/files/opencv-android/2.4.13/opencv-2.4.13.6-android-sdk.zip/download and unzip it to projectRoot and rename folder name as opencv

### Build

gradle build

gradle installDebug

### Tests

| Device             | Network       |  FPS  |
| ------------------ | ------------- | ----- |
| Samsung Galaxy S7  | SqueezeNet    |  5.8  |
| Google Pixel       | SqueezeNet    |  5.7  |

### License

Please see the LICENSE file in the root directory the source tree.
