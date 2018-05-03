package facebook.f8demo;

import android.Manifest;
import android.app.ActionBar;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Size;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE;

public class ClassifyCamera extends AppCompatActivity {
    private static final String TAG = "F8DEMO";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private TextureView textureView;
    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private TextView tv;
    private String predictedClass = "none";
    private AssetManager mgr;
    private boolean processing = false;
    private Image image = null;
    private boolean run_HWC = false;


    static {
        System.loadLibrary("native-lib");
    }

    public native String classificationFromCaffe2(int h, int w, byte[] data,
                                                  int rowStride, int pixelStride, boolean r_hwc);
    public native void initCaffe2(AssetManager mgr);
    private class SetUpNeuralNetwork extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void[] v) {
            try {
                initCaffe2(mgr);
                predictedClass = "Neural net loaded! Inferring...";
            } catch (Exception e) {
                Log.d(TAG, "Couldn't load neural network.");
            }
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        mgr = getResources().getAssets();

        new SetUpNeuralNetwork().execute();

        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);

        setContentView(R.layout.activity_classify_camera);

        textureView = (TextureView) findViewById(R.id.textureView);
        textureView.setSystemUiVisibility(SYSTEM_UI_FLAG_IMMERSIVE);
        final GestureDetector gestureDetector = new GestureDetector(this.getApplicationContext(),
                new GestureDetector.SimpleOnGestureListener(){
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                super.onLongPress(e);

            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        textureView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
            }
        });

        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);
        tv = (TextView) findViewById(R.id.sample_text);

    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };
    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }
    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            int width = 227;
            int height = 227;
            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4);
            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    try {

                        image = reader.acquireNextImage();
                        if (processing) {
                            image.close();
                            return;
                        }
                        processing = true;

                        // This won't be 227x227, device simply has its own support size: mCamera.getParameters().getSupportedPreviewSizes();
                        int w = image.getWidth();
                        int h = image.getHeight();

                        Image.Plane yPlane = image.getPlanes()[0];
                        Image.Plane uPlane = image.getPlanes()[1];
                        Image.Plane vPlane = image.getPlanes()[2];

                        // This is boundary for ByteBuffer
                        int ySize = yPlane.getBuffer().remaining();
                        int uSize = uPlane.getBuffer().remaining();
                        int vSize = vPlane.getBuffer().remaining();

                        byte[] data = new byte[w*h + ((w*h)/2)];

                        // For some details about YUV_420_888 and YUV_420SP (NV21)
                        // https://en.wikipedia.org/wiki/YUV#Y%E2%80%B2UV420sp_(NV21)_to_RGB_conversion_(Android)
                        // Y PLANE
                        // https://developer.android.com/reference/android/graphics/ImageFormat.html#YUV_420_888 
                        // The Y-plane is guaranteed not to be interleaved with the U/V planes (in particular, pixel stride is always 1 in yPlane.getPixelStride()).
                        // Carefully handle rowstride for y Plane (TODO: like uv, do this when necessary rowStride != w)
                        ByteBuffer yb = yPlane.getBuffer();
                        int yPixelStride = yPlane.getPixelStride();
                        int yRowStride = yPlane.getRowStride();
                        
                        Log.i(TAG,">>>>>>>>>>>>>> image format: " +image.getFormat());
                        Image.Plane[] planes = image.getPlanes();
                        for (int i = 0; i < planes.length; i++) {
                            Log.i(TAG, ">>>>>>>>>>>>>> planeIdx  " + i);
                            Log.i(TAG, ">>>>>>>>>>>>>> pixelStride  " + planes[i].getPixelStride());
                            Log.i(TAG, ">>>>>>>>>>>>>> rowStride   " + planes[i].getRowStride());
                            Log.i(TAG, ">>>>>>>>>>>>>> width  " + image.getWidth());
                            Log.i(TAG, ">>>>>>>>>>>>>> height  " + image.getHeight());
                            Log.i(TAG, ">>>>>>>>>>>>>> remain  " + planes[i].getBuffer().remaining());
                        }

                        int offset = 0;
                        for (int y = 0; y < h; ++y) {
                            int length = w;
                            yb.get(data, offset, length);
                            offset += length;
                            // move the pointer
                            if(yb.remaining() > 0) {
                                yb.position(yb.position() + yRowStride - length);
                            }
                        }

                        // UV PLANE, we need to make pattern
                        ByteBuffer ub = uPlane.getBuffer();
                        ByteBuffer vb = vPlane.getBuffer();
                        int uvPixelStride = uPlane.getPixelStride(); //stride guaranteed to be the same for u and v planes
                        int uvRowStride = uPlane.getRowStride();
                        
                        // We handle it carefully, universally (as we could) and slowly (You can optimize a bit when pixelStride = 1 or 2 or you end up writing your own YUV conversion and cropping like original demo)
                        // YYYYYYYYYYYYYYYYY;UUUUUUUUUU;VVVVVVVVV
                        for(int j = 0; j < h/2; ++j) {
                            int ln = 0;
                            for(int i = 0; i < w/2; ++i) {
                                uPlane.getBuffer().get(data, offset, 1);
                                ++offset;
                                if(ub.remaining() > 0) {
                                    ub.position(ub.position() + uvPixelStride - 1);
                                }
                            }
                            if(ub.remaining() > 0) {
                                ub.position(ub.position() + (uvRowStride - w));
                            }
                        }
                        for(int j = 0; j < h/2; ++j) {
                            int ln = 0;
                            for(int i = 0; i < w/2; ++i) {
                                vPlane.getBuffer().get(data, offset, 1);
                                ++offset;
                                if(vb.remaining() > 0) {
                                    vb.position(vb.position() + uvPixelStride - 1);
                                }
                            }
                            if(vb.remaining() > 0) {
                                vb.position(vb.position() + (uvRowStride - w));
                            }
                        }

                        // if (uvPixelStride == 1) {
                        //     uPlane.getBuffer().get(data, ySize, uSize);
                        //     vPlane.getBuffer().get(data, ySize + uSize, vSize);
                        // }
                        // else {
                            // if pixel stride is 2 there is padding between each pixel
                            // converting it to NV21 by filling the gaps of the v plane with the u values
                            // vb.get(data, ySize, vSize);
                            // for (int i = 0; i < uSize; i += 2) {
                            //     data[ySize + i + 1] = ub.get(i);
                            // }
                        // }

                        Log.i(TAG, "<<<<<<<<<<<<<<FINISHED");
                        // TODO: use these for proper image processing on different formats.
                        predictedClass = classificationFromCaffe2(h, w, data,
                                uvRowStride, uvPixelStride, run_HWC);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tv.setText(predictedClass);
                                processing = false;
                            }
                        });

                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            };
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            captureRequestBuilder.addTarget(reader.getSurface());

            cameraDevice.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (null == cameraDevice) {
                        return;
                    }
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(ClassifyCamera.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(ClassifyCamera.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void updatePreview() {
        if(null == cameraDevice) {
            return;
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(ClassifyCamera.this, "You can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
}
