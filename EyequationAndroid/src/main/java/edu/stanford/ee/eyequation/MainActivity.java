package edu.stanford.ee.eyequation;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.googlecode.tesseract.android.TessBaseAPI;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.List;

import edu.stanford.ee.eyequation.camera.CameraConfigurationManager;
import edu.stanford.ee.eyequation.imageProc.PlanarYUVLuminanceSource;

public class MainActivity extends Activity {

    private String TAG = "MainActivity";

    /** Resource to use for data file downloads. */
    static final String DOWNLOAD_BASE = "http://tesseract-ocr.googlecode.com/files/";

    /** Download filename for orientation and script detection (OSD) data. */
    static final String OSD_FILENAME = "tesseract-ocr-3.01.osd.tar";

    /** Destination filename for orientation and script detection (OSD) data. */
    static final String OSD_FILENAME_BASE = "osd.traineddata";

    // Whether or not the camera is previewing. If camera is not previewing,
    // then we must be viewing the static image of the equation(s)
    boolean isCameraPreviewing = true;

    // Callback for receiving a preview frame from the camera
    private CameraPreviewCallback cameraPreviewCB;

    private class CameraPreviewCallback implements Camera.PreviewCallback {

        public CameraPreviewCallback() {}

        @Override
        public void onPreviewFrame(byte[] bytes, Camera camera) {
            handlePreviewFrame(bytes, camera);
        }
    }

    private static Camera mCamera;
    private CameraConfigurationManager configManager;
    private final String focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;

    // What preview format to use.
    private int previewFormat = ImageFormat.NV21; // ImageFormat.RGB_565 would take some refactoring to use

    // View Components
    private FrameLayout previewFrame;
    private CameraPreview mPreview;
    private Button captureButton;
    private ImageView pictureView;

    // Info related to the most recently captured image frame
    private Bitmap currentFrame;
    private Bitmap currentFrameRaw; // the raw frame
    private Bitmap currentFrameBW; // black and white
    int currentFrameWidth;
    int currentFrameHeight;

    // List of rectangles around each equation
    private List<Rect> equationRectangles;

    private ProgressDialog dialog; // for initOcr - language download & unzip
    private ProgressDialog indeterminateDialog; // also for initOcr - init OCR engine

    private TessBaseAPI baseApi;

    // Set Tesseract as the OCR engine (don't use Cube)
    private int ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;

    // Tell Tesseract to treat its input as a single line of text
    private int pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE; //TessBaseAPI.PageSegMode.PSM_AUTO_OSD;

    private String characterBlacklist;
    private String characterWhitelist;

    // TODO: clean this up
    private String sourceLanguageCodeOcr = "eng"; // ISO 639-3 language code
    private String sourceLanguageReadable = "English"; // Language name, for example, "English"

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Keep the app awake. Helpful for demo purposes
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Attempt to load OpenCV
        if (!OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_11, this, mOvenCVLoaderCallback)) {
            Toast toast = Toast.makeText(getApplicationContext(), "Failed to load OpenCV! Check that you have OpenCV Manager.", Toast.LENGTH_LONG);
            toast.show();
        }

        initCharacterBlacklistAndWhitelist();

        // Initialize the OCR engine
        File storageDirectory = getStorageDirectory();
        if (storageDirectory != null) {
            initOcrEngine(storageDirectory, sourceLanguageCodeOcr, sourceLanguageReadable);
        }

        setupCameraPreview();

    }

    private void initCharacterBlacklistAndWhitelist() {
        characterBlacklist = "";
        characterWhitelist = "0123456789()-+x/";
    }

    /** Finds the proper location on the SD card where we can save files. */
    private File getStorageDirectory() {

        String state = null;
        try {
            state = Environment.getExternalStorageState();
        } catch (RuntimeException e) {
            Log.e(TAG, "Is the SD card visible?", e);
            showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable.");
        }

        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {

            // We can read and write the media
            //    	if (Integer.valueOf(android.os.Build.VERSION.SDK_INT) > 7) {
            // For Android 2.2 and above

            try {
                return getExternalFilesDir(Environment.MEDIA_MOUNTED);
            } catch (NullPointerException e) {
                // We get an error here if the SD card is visible, but full
                Log.e(TAG, "External storage is unavailable");
                showErrorMessage("Error", "Required external storage (such as an SD card) is full or unavailable.");
            }

        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            // We can only read the media
            Log.e(TAG, "External storage is read-only");
            showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable for data storage.");
        } else {
            // Something else is wrong. It may be one of many other states, but all we need
            // to know is we can neither read nor write
            Log.e(TAG, "External storage is unavailable");
            showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable or corrupted.");
        }
        return null;
    }

    /**
     * Displays an error message dialog box to the user on the UI thread.
     *
     * @param title The title for the dialog box
     * @param message The error message to be displayed
     */
    void showErrorMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setOnCancelListener(new FinishListener(this))
                .setPositiveButton( "Done", new FinishListener(this))
                .show();
    }

    // Callback for loading OpenCV using OpenCV Manager
    private BaseLoaderCallback mOvenCVLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                    Log.i(TAG, "OpenCV loaded successfully");
                    break;
                default:
                    // Handle error after callback
                    super.onManagerConnected(status);
                    break;
            }
        }
    };

    private void setupCameraPreview() {

        // Create an instance of Camera
        configManager = new CameraConfigurationManager(this);
        mCamera = configManager.getCameraInstance(focusMode, previewFormat);
        cameraPreviewCB = new CameraPreviewCallback();

        // Create our Preview view and set it as the content of our activity.
        mPreview = new CameraPreview(this, mCamera);
        previewFrame = (FrameLayout) findViewById(R.id.camera_preview);
        previewFrame.addView(mPreview);

        pictureView = (ImageView) findViewById(R.id.picture_view);

        // Add a listener to the Capture button
        captureButton = (Button) findViewById(R.id.button_capture);
        captureButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // get an image from the camera
                        //mCamera.takePicture(null, null, mPictureCB);

                        // Request a frame from the camera
                        mCamera.setOneShotPreviewCallback(cameraPreviewCB);

                    }
                }
        );
    }

    // Perform all processing steps starting from a raw byte array representing
    // the image in NV21 format to the final solved equation(s)
    public void handlePreviewFrame(byte[] data, Camera camera) {

        // Hide the camera preview so we can overlay the single frame
        hideCameraPreview();

        Point cameraResolution = configManager.getCameraResolution();

        currentFrameWidth = cameraResolution.x;
        currentFrameHeight = cameraResolution.y;

        currentFrameRaw = NV21BytesToGrayScaleBitmap(data, currentFrameWidth, currentFrameHeight);
        //int[] pixels = convertYUV420_NV21toRGB8888(data, width, height);
        //currentFrameRaw = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        currentFrame = currentFrameRaw;
        currentFrameBW = locallyAdaptiveThreshold(currentFrame);

        // Display the captured image
        pictureView.setImageBitmap(currentFrame);
        pictureView.setVisibility(View.VISIBLE);

        // Perform the rest of the pipeline, including rectangle finding,
        // OCR, and equation parsing
        processImage(currentFrame, currentFrameBW, currentFrameWidth, currentFrameHeight);
    }

    private Bitmap locallyAdaptiveThreshold(Bitmap gray) {
        Bitmap newBitmap = Bitmap.createBitmap(gray.getWidth(), gray.getHeight(), Bitmap.Config.ARGB_8888);

        Mat mat = new Mat(); //Mat.zeros(imgBitmap.getHeight(), imgBitmap.getWidth(), CvType.CV_8UC4);
        Utils.bitmapToMat(gray, mat);
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);

        Mat matBW = new Mat(); //Mat.zeros(imgBitmap.getHeight(), imgBitmap.getWidth(), CvType.CV_8UC1);

        // Block size for thresholding. MUST BE AN ODD NUMBER or openCV will throw an exception!
        int blockSize = 55;
        Imgproc.adaptiveThreshold(mat, matBW, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, blockSize, 15);
        Utils.matToBitmap(matBW, newBitmap);

        return newBitmap;

    }

    private Camera.PictureCallback mPictureCB = new Camera.PictureCallback() {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {

            currentFrameWidth = camera.getParameters().getPreviewSize().width;
            currentFrameHeight = camera.getParameters().getPreviewSize().height;

            currentFrameRaw = BitmapFactory.decodeByteArray(data, 0, data.length);
            currentFrame = currentFrameRaw;
            currentFrameBW = locallyAdaptiveThreshold(currentFrame);
            //PlanarYUVLuminanceSource lum = new PlanarYUVLuminanceSource(data, imgBitmap.getWidth(), imgBitmap.getHeight(),
             //       0, 0,
              //      imgBitmap.getWidth(), imgBitmap.getHeight(), false);


            pictureView.setImageBitmap(currentFrame);
            pictureView.setVisibility(View.VISIBLE);

            hideCameraPreview();

            processImage(currentFrame, currentFrameBW, currentFrameWidth, currentFrameHeight);

        }
    };

    ProgressDialog getProgressDialog() {
        return indeterminateDialog;
    }

    /**
     * Convert an NV21 formatted array to a grayscale bitmap of the image.
     */
    private Bitmap NV21BytesToGrayScaleBitmap(byte[] data, int width, int height) {
        PlanarYUVLuminanceSource lum = new PlanarYUVLuminanceSource(data, width, height,
                0, 0, width, height, false);
        return lum.renderCroppedGreyscaleBitmap();
    }

    private void processImage(Bitmap bitmap, Bitmap bitmapBW, int width, int height) {
        new FindEqnRectsAsyncTask(this, baseApi, bitmap, bitmapBW, width, height)
                .execute();
    }

    public void handleEquationResult(EquationResult equationResult) {

        // Redraw this equation's rectangle with a color that indicates whether
        // a solution was found (green) or not (red)
        int rectColor;
        boolean success = equationResult.isSuccess();
        if(success) {
            rectColor = Color.GREEN;
        }
        else {
            rectColor = Color.RED;
        }
        redrawEquationRect(null, equationResult.getEquationNumber(), rectColor);

        // Display the result (which may or may not be successful)
        drawEquationResult(success, equationResult.getOcrText(),  equationResult.getSolution(), equationResult.getEquationNumber());
    }

    private void hideCameraPreview() {
        previewFrame.setVisibility(View.GONE);
        captureButton.setVisibility(View.GONE);
        isCameraPreviewing = false;
    }

    private void showCameraPreview() {
        previewFrame.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.VISIBLE);
        isCameraPreviewing = true;
    }

    @Override
    public void onBackPressed() {
        if( isCameraPreviewing ) {
            super.onBackPressed();
        }
        else {
            // Return to the camera preview
            pictureView.setVisibility(View.GONE);
            mCamera.startPreview();
            showCameraPreview();
        }

    }

    /**
     * Requests initialization of the OCR engine with the given parameters.
     *
     * @param storageRoot Path to location of the tessdata directory to use
     * @param languageCode Three-letter ISO 639-3 language code for OCR
     * @param languageName Name of the language for OCR, for example, "English"
     */
    private void initOcrEngine(File storageRoot, String languageCode, String languageName) {

        // Set up the dialog box for the thermometer-style download progress indicator
        if (dialog != null) {
            dialog.dismiss();
        }
        dialog = new ProgressDialog(this);

        // Display the name of the OCR engine we're initializing in the indeterminate progress dialog box
        indeterminateDialog = new ProgressDialog(this);
        indeterminateDialog.setTitle("Please wait");
        String ocrEngineModeName = "Tesseract";
        indeterminateDialog.setMessage("Initializing " + ocrEngineModeName + " OCR engine for " + languageName + "...");
        indeterminateDialog.setCancelable(false);
        indeterminateDialog.show();

        // Start AsyncTask to install language data and init OCR
        baseApi = new TessBaseAPI();
        new OcrInitAsyncTask(this, baseApi, dialog, indeterminateDialog, languageCode, languageName, ocrEngineMode)
                .execute(storageRoot.toString());
    }

    public void drawEquationResult(boolean success, String ocrEquationStr, Double result, int equationNumber) {

        //Create a new image bitmap and attach a brand new canvas to it
        Bitmap newBitmap = Bitmap.createBitmap(currentFrame.getWidth(), currentFrame.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(newBitmap);

        //Draw the image bitmap into the canvas
        canvas.drawBitmap(currentFrame, 0, 0, null);

        // Set up the paint to be drawn on the canvas
        Paint paint = new Paint();
        if(success) {
            paint.setColor(Color.GREEN);
        }
        else {
            paint.setColor(Color.RED);
        }

        paint.setTextSize(14);
        paint.setStrokeWidth(12);

        // Write the text near the top left of the rectangle if there's room
        // TODO: if rect fills the entire bitmap, put the result inside of the rect
        // or toast the result. use the displayResultInsideRect
        Rect rect = equationRectangles.get(equationNumber);
        String resultStr = ocrEquationStr + " = " + String.valueOf(result);

        Point drawResultAt = locationForResult(rect, resultStr);
        canvas.drawText(resultStr, drawResultAt.x, drawResultAt.y, paint);

        // Display the image with rectangles on it
        pictureView.setImageDrawable(new BitmapDrawable(getResources(), newBitmap));
        currentFrame = newBitmap;

    }

    private Point locationForResult(Rect rect, String resultStr) {

        int locY;

        if(rect.y > 10) {
            // There's enough room to draw at the top
            locY = rect.y - 2;
        }
        else if(rect.y + rect.height <= currentFrameHeight - 10) {
            // There's enough room to draw below
            locY = rect.y + rect.height + 16;
        }
        else {
            // No room at top or bottom. Put the result inside the top left corner
            locY = rect.y + 16;
        }

        return new Point(rect.x, locY);
    }

    public void redrawEquationRect(Rect rect, int equationNumber, int color) {

        if(rect == null) {
            rect = equationRectangles.get(equationNumber);
        }

        //Create a new image bitmap and attach a brand new canvas to it
        Bitmap newBitmap = Bitmap.createBitmap(currentFrame.getWidth(), currentFrame.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(newBitmap);

        //Draw the image bitmap into the canvas
        canvas.drawBitmap(currentFrame, 0, 0, null);

        // Set up the paint to be drawn on the canvas
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);

        // Redraw the rectangle
        android.graphics.Rect rectGraphic = new android.graphics.Rect((int) rect.tl().x, (int) rect.tl().y,
                (int) rect.br().x, (int) rect.br().y);
        canvas.drawRect(rectGraphic, paint);

        // Display the image with rectangles on it
        pictureView.setImageDrawable(new BitmapDrawable(getResources(), newBitmap));
        currentFrame = newBitmap;
    }

    public void drawEquationRects(List<Rect> rectList) {

        equationRectangles = rectList;

        //Create a new image bitmap and attach a brand new canvas to it
        Bitmap newBitmap = Bitmap.createBitmap(currentFrame.getWidth(), currentFrame.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(newBitmap);

        //Draw the image bitmap into the canvas
        canvas.drawBitmap(currentFrame, 0, 0, null);

        // Set up the paint to be drawn on the canvas
        Paint paint = new Paint();
        paint.setColor(Color.YELLOW);
        paint.setStyle(Paint.Style.STROKE);

        // Draw each rectangle
        for(Rect rect : rectList) {
            android.graphics.Rect rectGraphic = new android.graphics.Rect((int) rect.tl().x, (int) rect.tl().y,
                    (int) rect.br().x, (int) rect.br().y);
            canvas.drawRect(rectGraphic, paint);
        }

        // Display the image with rectangles on it
        pictureView.setImageDrawable(new BitmapDrawable(getResources(), newBitmap));
        currentFrame = newBitmap;
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");

        // Stop using the camera, to avoid conflicting with other camera-based apps
        // Before releasing the camera, remove the preview callback to avoid a "method
        // called after release()" error
        if(mCamera != null) {
            mCamera.stopPreview();

            previewFrame.removeView(mPreview);
            mCamera.setPreviewCallback(null);
            mCamera.release();
            mCamera = null;
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();

        if(mCamera == null) {
            Log.d(TAG, "re-initializing camera");
            setupCameraPreview();
        }

        // Do OCR engine initialization, if necessary
        if (baseApi == null) {
            // Initialize the OCR engine
            File storageDirectory = getStorageDirectory();
            if (storageDirectory != null) {
                initOcrEngine(storageDirectory, sourceLanguageCodeOcr, sourceLanguageReadable);
            }
        } else {
            // We already have the engine initialized, so just start the camera.
            resumeOCR();
        }
    }



    /**
     * Method to start or restart recognition after the OCR engine has been initialized,
     * or after the app regains focus. Sets state related settings and OCR engine parameters,
     * and requests camera initialization.
     */
    void resumeOCR() {
        Log.d(TAG, "resumeOCR()");

        if (baseApi != null) {
            baseApi.setPageSegMode(pageSegmentationMode);
            baseApi.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, characterBlacklist);
            baseApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, characterWhitelist);
        }
    }

    // Adapted from:
    // http://stackoverflow.com/questions/5272388/extract-black-and-white-image-from-android-cameras-nv21-format/12702836#12702836
    /**
     * Converts YUV420 NV21 to RGB8888
     *
     * @param data byte array on YUV420 NV21 format.
     * @param width pixels width
     * @param height pixels height
     * @return a RGB8888 pixels int array. Where each int is a pixels ARGB.
     */
    public static int[] convertYUV420_NV21toRGB8888(byte [] data, int width, int height) {
        int size = width*height;
        int offset = size;
        int[] pixels = new int[size];
        int u, v, y1, y2, y3, y4;

        // i percorre os Y and the final pixels
        // k percorre os pixles U e V
        for(int i=0, k=0; i < size; i+=2, k+=2) {
            y1 = data[i  ]&0xff;
            y2 = data[i+1]&0xff;
            y3 = data[width+i  ]&0xff;
            y4 = data[width+i+1]&0xff;

            u = data[offset+k  ]&0xff;
            v = data[offset+k+1]&0xff;
            u = u-128;
            v = v-128;

            pixels[i  ] = convertYUVtoRGB(y1, u, v);
            pixels[i+1] = convertYUVtoRGB(y2, u, v);
            pixels[width+i  ] = convertYUVtoRGB(y3, u, v);
            pixels[width+i+1] = convertYUVtoRGB(y4, u, v);

            if (i!=0 && (i+2)%width==0)
                i+=width;
        }

        return pixels;
    }

    private static int convertYUVtoRGB(int y, int u, int v) {
        int r,g,b;

        r = y + (int)1.402f*v;
        g = y - (int)(0.344f*u +0.714f*v);
        b = y + (int)1.772f*u;
        r = r>255? 255 : r<0 ? 0 : r;
        g = g>255? 255 : g<0 ? 0 : g;
        b = b>255? 255 : b<0 ? 0 : b;
        return 0xff000000 | (b<<16) | (g<<8) | r;
    }

}
