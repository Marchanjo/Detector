package com.dji.Detector;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.OrientationHelper;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SlidingDrawer;
import android.widget.TextView;
import android.widget.Toast;

import com.dji.Detector.Insulator_DetectorApplication;
import com.dji.Detector.env.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJICameraError;
import dji.common.error.DJIError;
import dji.common.util.CommonCallbacks;
import dji.log.DJILog;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.FetchMediaTask;
import dji.sdk.media.FetchMediaTaskContent;
import dji.sdk.media.FetchMediaTaskScheduler;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;
//import dji.sdk.camera.DownloadListener;
//import dji.sdk.camera.FetchMediaTask;
//import dji.sdk.camera.FetchMediaTaskContent;
//import dji.sdk.camera.FetchMediaTaskScheduler;
//import dji.sdk.camera.MediaFile;
//import dji.sdk.camera.MediaManager;

public class MediaActivity extends Activity implements View.OnClickListener {

  //  private static final String TAG = MediaActivity.class.getName();
    private Logger LOGGER = new Logger();
    private Button mBackBtn, mDeleteBtn, mReloadBtn, mDownloadBtn, mStatusBtn;
    private Button mPlayBtn, mResumeBtn, mPauseBtn, mStopBtn, mMoveToBtn;
    private RecyclerView listView;
    private FileListAdapter mListAdapter;
    private List<MediaFile> mediaFileList = new ArrayList<MediaFile>();
    private MediaManager mMediaManager;
    private MediaManager.FileListState currentFileListState = MediaManager.FileListState.UNKNOWN;
    private FetchMediaTaskScheduler scheduler;
    private ProgressDialog mLoadingDialog;
    private ProgressDialog mDownloadDialog;
    private SlidingDrawer mPushDrawerSd;
    File destDir;
    private int currentProgress = -1;
    private ImageView mDisplayImageView;
    private int lastClickViewIndex =-1;
    private View lastClickView;
    private TextView mPushTv;
    private SettingsDefinitions.StorageLocation loc;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media);
        initUI();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Insulator_DetectorApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);

    }
    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            Intent intent1 = new Intent(getApplicationContext(), ConnectionActivity.class);
            intent1.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivityIfNeeded(intent1,0);
        }
    };
    @Override
    protected void onResume() {
        LOGGER.e( "onResume");
        super.onResume();
        initMediaManager();
    }

    @Override
    protected void onPause() {
        LOGGER.e( "onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        LOGGER.e("onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        LOGGER.e("onDestroy");
        if(mReceiver!=null){
            unregisterReceiver(mReceiver);
        }

        lastClickView = null;
        if (mMediaManager != null) {
            mMediaManager.stop(null);
            mMediaManager.removeFileListStateCallback(this.updateFileListStateListener);
            mMediaManager.removeMediaUpdatedVideoPlaybackStateListener(updatedVideoPlaybackStateListener);
            mMediaManager.exitMediaDownloading();
            if (scheduler != null) {
                scheduler.removeAllTasks();
            }
        }
        Camera camera = Insulator_DetectorApplication.getCameraInstance();
        if (camera != null){
            camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError mError) {
                    if (mError != null) {
                        LOGGER.e("Set Shoot Photo Mode Failed" + mError.getDescription());
                      //  setResultToToast("Set Shoot Photo Mode Failed" + mError.getDescription());
                    }
                    else{
                        LOGGER.e("OK");

                    }
                }
            });
         }

        if (mediaFileList != null) {
            mediaFileList.clear();
        }
        super.onDestroy();
    }

    void initUI() {
        File x = Environment.getExternalStorageDirectory();
        destDir = new File(x.getPath() + "/MediaManagerDemo/");
        //Init RecyclerView
        listView = (RecyclerView) findViewById(R.id.filelistView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getApplicationContext(), OrientationHelper.VERTICAL,false);
        listView.setLayoutManager(layoutManager);

        //Init FileListAdapter
        mListAdapter = new FileListAdapter();
        listView.setAdapter(mListAdapter);

        //Init Loading Dialog
        mLoadingDialog = new ProgressDialog(getApplicationContext());
        mLoadingDialog.setMessage("Please wait");
        mLoadingDialog.setCanceledOnTouchOutside(false);
        mLoadingDialog.setCancelable(false);

        //Init Download Dialog
        mDownloadDialog = new ProgressDialog(getApplicationContext());
        mDownloadDialog.setTitle("Downloading file");
        mDownloadDialog.setIcon(android.R.drawable.ic_dialog_info);
        mDownloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mDownloadDialog.setCanceledOnTouchOutside(false);
        mDownloadDialog.setCancelable(true);
        mDownloadDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if (mMediaManager != null) {
                    mMediaManager.exitMediaDownloading();
                }
            }
        });

        mPushDrawerSd = (SlidingDrawer)findViewById(R.id.pointing_drawer_sd);
        mPushTv = (TextView)findViewById(R.id.pointing_push_tv);
        mBackBtn = (Button) findViewById(R.id.back_btn);
        mDeleteBtn = (Button) findViewById(R.id.delete_btn);
        mDownloadBtn = (Button) findViewById(R.id.download_btn);
        mReloadBtn = (Button) findViewById(R.id.reload_btn);
        mStatusBtn = (Button) findViewById(R.id.status_btn);
        mPlayBtn = (Button) findViewById(R.id.play_btn);
        mResumeBtn = (Button) findViewById(R.id.resume_btn);
        mPauseBtn = (Button) findViewById(R.id.pause_btn);
        mStopBtn = (Button) findViewById(R.id.stop_btn);
        mMoveToBtn = (Button) findViewById(R.id.moveTo_btn);
        mDisplayImageView = (ImageView) findViewById(R.id.imageView);
        mDisplayImageView.setVisibility(View.VISIBLE);

        mBackBtn.setOnClickListener(this);
        mDeleteBtn.setOnClickListener(this);
        mDownloadBtn.setOnClickListener(this);
        mReloadBtn.setOnClickListener(this);
        mDownloadBtn.setOnClickListener(this);
        mStatusBtn.setOnClickListener(this);
        mPlayBtn.setOnClickListener(this);
        mResumeBtn.setOnClickListener(this);
        mPauseBtn.setOnClickListener(this);
        mStopBtn.setOnClickListener(this);
        mMoveToBtn.setOnClickListener(this);

    }

    private void showProgressDialog() {
        runOnUiThread(new Runnable() {
            public void run() {
                if (mLoadingDialog != null) {
                    mLoadingDialog.show();
                }
            }
        });
    }

    private void hideProgressDialog() {

        runOnUiThread(new Runnable() {
            public void run() {
                if (null != mLoadingDialog && mLoadingDialog.isShowing()) {
                    mLoadingDialog.dismiss();
                }
            }
        });
    }

    private void ShowDownloadProgressDialog() {
        if (mDownloadDialog != null) {
            runOnUiThread(new Runnable() {
                public void run() {
                    mDownloadDialog.incrementProgressBy(-mDownloadDialog.getProgress());
                    mDownloadDialog.show();
                }
            });
        }
    }

    private void HideDownloadProgressDialog() {

        if (null != mDownloadDialog && mDownloadDialog.isShowing()) {
            runOnUiThread(new Runnable() {
                public void run() {
                    mDownloadDialog.dismiss();
                }
            });
        }
    }

    private void setResultToToast(final String result) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), result, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setResultToText(final String string) {
        if (mPushTv == null) {
            setResultToToast("Push info tv has not be init...");
        }
       MediaActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mPushTv.setText(string);
            }
        });
    }

    private void initMediaManager() {
        LOGGER.e("INITMEDIAMANAGER()");
        BaseProduct product =Insulator_DetectorApplication.getProductInstance();
        if (product == null) {
            mediaFileList.clear();
            mListAdapter.notifyDataSetChanged();
            LOGGER.e("Product disconnected");
          //  DJILog.e(TAG, "Product disconnected");
            return;
        } else {
            Camera camera =Insulator_DetectorApplication.getCameraInstance();
            if (null != camera && camera.isMediaDownloadModeSupported()) {
                mMediaManager = camera.getMediaManager();
                if (null != mMediaManager) {
                    mMediaManager.addUpdateFileListStateListener(this.updateFileListStateListener);
                    mMediaManager.addMediaUpdatedVideoPlaybackStateListener(this.updatedVideoPlaybackStateListener);
                    camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {
                            if (error == null) {
                                LOGGER.e("Set cameraMode success");
                               // DJILog.e(TAG, "Set cameraMode success");
                               // showProgressDialog();
                               getFileList();
                            } else {
                                LOGGER.e("Set cameraMode failed");
                                //setResultToToast("Set cameraMode failed");
                            }
                        }
                    });
                    if (mMediaManager.isVideoPlaybackSupported()) {
                        LOGGER.e("Camera support video playback!");
                     //   DJILog.e(TAG, "Camera support video playback!");
                    } else {
                        LOGGER.e("Camera does not support video playback!");
                      //  setResultToToast("Camera does not support video playback!");
                    }
                    scheduler = mMediaManager.getScheduler();
                }

            } else if (null != camera
                    && !camera.isMediaDownloadModeSupported()) {
                setResultToToast("Media Download Mode not Supported");
            }
        }

    }

    private void getFileList() {
        Camera camera =Insulator_DetectorApplication.getCameraInstance();
         if (camera != null){
            mMediaManager = camera.getMediaManager();
           if (mMediaManager != null) {

                 if ((currentFileListState == MediaManager.FileListState.SYNCING) || (currentFileListState == MediaManager.FileListState.DELETING)){
                    LOGGER.e("Media Manager is busy.");
                   // DJILog.e(TAG, "Media Manager is busy.");
                }else{
                        LOGGER.e("BOOP BOOP BOOP BOOP ");

                    camera.getStorageLocation(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.StorageLocation>() {
                        @Override
                        public void onSuccess(SettingsDefinitions.StorageLocation storageLocation) {
                            // File list setup inside this onSuccess function so that no memory leak happens as it did in the sample code
                            loc = storageLocation;
                            LOGGER.e("STORAGE LOC = " + loc);
                          //  loc = SettingsDefinitions.StorageLocation.SDCARD;
                            mMediaManager.refreshFileListOfStorageLocation(storageLocation, new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {

                                    LOGGER.e("BOOPAA BOOPAA BOOPAA BOOPAA ");
                                    if (null == djiError) {
                                        hideProgressDialog();

                                        //Reset data
                                        if (currentFileListState != MediaManager.FileListState.INCOMPLETE) {
                                            mediaFileList.clear();
                                            lastClickViewIndex = -1;
                                            lastClickView = null;
                                        }

                                        mediaFileList = mMediaManager.getSDCardFileListSnapshot();
                                        Collections.sort(mediaFileList, new Comparator<MediaFile>() {
                                            @Override
                                            public int compare(MediaFile lhs, MediaFile rhs) {
                                                if (lhs.getTimeCreated() < rhs.getTimeCreated()) {
                                                    return 1;
                                                } else if (lhs.getTimeCreated() > rhs.getTimeCreated()) {
                                                    return -1;
                                                }
                                                return 0;
                                            }
                                        });
                                        scheduler.resume(new CommonCallbacks.CompletionCallback() {
                                            @Override
                                            public void onResult(DJIError error) {
                                                if (error == null) {
                                                    getThumbnails();
                                                    getPreviews();
                                                }
                                            }
                                        });
                                    } else {
                                        hideProgressDialog();
                                        LOGGER.e("Get Media File List Failed:" + djiError.getDescription());
                                        //  setResultToToast("Get Media File List Failed:" + djiError.getDescription());
                                    }
                                }
                            });
                        }

                        @Override
                        public void onFailure(DJIError djiError) {

                        }
                    });


                    // This is the same as implemented above, but outside the getStorageLocation function. THIS USED TO CAUSE MEMORY LEAK
                    /* loc = SettingsDefinitions.StorageLocation.SDCARD;
                     mMediaManager.refreshFileListOfStorageLocation(loc, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {

                            LOGGER.e("BOOPAA BOOPAA BOOPAA BOOPAA ");
                             if (null == djiError) {
                                hideProgressDialog();

                                //Reset data
                                if (currentFileListState != MediaManager.FileListState.INCOMPLETE) {
                                    mediaFileList.clear();
                                    lastClickViewIndex = -1;
                                    lastClickView = null;
                                }

                                 mediaFileList = mMediaManager.getSDCardFileListSnapshot();
                                Collections.sort(mediaFileList, new Comparator<MediaFile>() {
                                    @Override
                                    public int compare(MediaFile lhs, MediaFile rhs) {
                                        if (lhs.getTimeCreated() < rhs.getTimeCreated()) {
                                            return 1;
                                        } else if (lhs.getTimeCreated() > rhs.getTimeCreated()) {
                                            return -1;
                                        }
                                        return 0;
                                    }
                                });
                                scheduler.resume(new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError error) {
                                        if (error == null) {
                                             getThumbnails();
                                             getPreviews();
                                        }
                                    }
                                });
                            } else {
                                hideProgressDialog();
                                LOGGER.e("Get Media File List Failed:" + djiError.getDescription());
                              //  setResultToToast("Get Media File List Failed:" + djiError.getDescription());
                            }
                        }
                    });*/

                }
            }

        }

    }

    private void getThumbnails() {
        if (mediaFileList.size() <= 0) {
            setResultToToast("No File info for downloading thumbnails");
            return;
        }
        for (int i = 0; i < mediaFileList.size(); i++) {
            getThumbnailByIndex(i);
        }
    }

    private void getPreviews() {
        if (mediaFileList.size() <= 0) {
            setResultToToast("No File info for downloading previews");
            return;
        }
        for (int i = 0; i < mediaFileList.size(); i++) {
            getPreviewByIndex(i);
        }
    }

    private FetchMediaTask.Callback taskCallback = new FetchMediaTask.Callback() {
        @Override
        public void onUpdate(MediaFile file, FetchMediaTaskContent option, DJIError error) {
            if (null == error) {
                if (option == FetchMediaTaskContent.PREVIEW) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mListAdapter.notifyDataSetChanged();
                        }
                    });
                }
                if (option == FetchMediaTaskContent.THUMBNAIL) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mListAdapter.notifyDataSetChanged();
                        }
                    });
                }
            } else {
                LOGGER.e("Fetch Media Task Failed" + error.getDescription());
               // DJILog.e(TAG, "Fetch Media Task Failed" + error.getDescription());
            }
        }
    };

    private void getThumbnailByIndex(final int index) {
        FetchMediaTask task = new FetchMediaTask(mediaFileList.get(index), FetchMediaTaskContent.THUMBNAIL, taskCallback);
        scheduler.moveTaskToEnd(task);
    }

    private void getPreviewByIndex(final int index) {
        FetchMediaTask task = new FetchMediaTask(mediaFileList.get(index), FetchMediaTaskContent.PREVIEW, taskCallback);
        scheduler.moveTaskToEnd(task);
    }

    private class ItemHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail_img;
        TextView file_name;
        TextView file_type;
        TextView file_size;
        TextView file_time;

        public ItemHolder(View itemView) {
            super(itemView);
            this.thumbnail_img = (ImageView) itemView.findViewById(R.id.filethumbnail);
            this.file_name = (TextView) itemView.findViewById(R.id.filename);
            this.file_type = (TextView) itemView.findViewById(R.id.filetype);
            this.file_size = (TextView) itemView.findViewById(R.id.fileSize);
            this.file_time = (TextView) itemView.findViewById(R.id.filetime);
        }
    }

    private class FileListAdapter extends RecyclerView.Adapter<ItemHolder> {
        @Override
        public int getItemCount() {
            if (mediaFileList != null) {
                return mediaFileList.size();
            }
            return 0;
        }

        @Override
        public ItemHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.media_info_item, parent, false);
            return new ItemHolder(view);
        }

        @Override
        public void onBindViewHolder(ItemHolder mItemHolder, final int index) {

            final MediaFile mediaFile = mediaFileList.get(index);
            if (mediaFile != null) {
                if (mediaFile.getMediaType() != MediaFile.MediaType.MOV && mediaFile.getMediaType() != MediaFile.MediaType.MP4) {
                    mItemHolder.file_time.setVisibility(View.GONE);
                } else {
                    mItemHolder.file_time.setVisibility(View.VISIBLE);
                    mItemHolder.file_time.setText(mediaFile.getDurationInSeconds() + " s");
                }
                mItemHolder.file_name.setText(mediaFile.getFileName());
                mItemHolder.file_type.setText(mediaFile.getMediaType().name());
                mItemHolder.file_size.setText(mediaFile.getFileSize() + " Bytes");
                mItemHolder.thumbnail_img.setImageBitmap(mediaFile.getThumbnail());
                mItemHolder.thumbnail_img.setOnClickListener(ImgOnClickListener);
                mItemHolder.thumbnail_img.setTag(mediaFile);
                mItemHolder.itemView.setTag(index);

                if (lastClickViewIndex == index) {
                    mItemHolder.itemView.setSelected(true);
                } else {
                    mItemHolder.itemView.setSelected(false);
                }
                mItemHolder.itemView.setOnClickListener(itemViewOnClickListener);

            }
        }
    }

    private View.OnClickListener itemViewOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            lastClickViewIndex = (int) (v.getTag());

            if (lastClickView != null && lastClickView != v) {
                lastClickView.setSelected(false);
            }
            v.setSelected(true);
            lastClickView = v;
        }
    };

    private View.OnClickListener ImgOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            MediaFile selectedMedia = (MediaFile )v.getTag();
            final Bitmap previewImage = selectedMedia.getPreview();
            runOnUiThread(new Runnable() {
                public void run() {
                    mDisplayImageView.setVisibility(View.VISIBLE);
                    mDisplayImageView.setImageBitmap(previewImage);
                }
            });
        }
    };

    //Listeners
    private MediaManager.FileListStateListener updateFileListStateListener = new MediaManager.FileListStateListener() {
        @Override
        public void onFileListStateChange(MediaManager.FileListState state) {
            currentFileListState = state;
        }
    };

    private MediaManager.VideoPlaybackStateListener updatedVideoPlaybackStateListener =
            new MediaManager.VideoPlaybackStateListener() {
                @Override
                public void onUpdate(MediaManager.VideoPlaybackState videoPlaybackState) {
                    updateStatusTextView(videoPlaybackState);
                }
            };

    private void updateStatusTextView(MediaManager.VideoPlaybackState videoPlaybackState) {
        final StringBuffer pushInfo = new StringBuffer();

        addLineToSB(pushInfo, "Video Playback State", null);
        if (videoPlaybackState != null) {
            if (videoPlaybackState.getPlayingMediaFile() != null) {
                addLineToSB(pushInfo, "media index", videoPlaybackState.getPlayingMediaFile().getIndex());
                addLineToSB(pushInfo, "media size", videoPlaybackState.getPlayingMediaFile().getFileSize());
                addLineToSB(pushInfo,
                        "media duration",
                        videoPlaybackState.getPlayingMediaFile().getDurationInSeconds());
                addLineToSB(pushInfo, "media created date", videoPlaybackState.getPlayingMediaFile().getDateCreated());
                addLineToSB(pushInfo,
                        "media orientation",
                        videoPlaybackState.getPlayingMediaFile().getVideoOrientation());
            } else {
                addLineToSB(pushInfo, "media index", "None");
            }
            addLineToSB(pushInfo, "media current position", videoPlaybackState.getPlayingPosition());
            addLineToSB(pushInfo, "media current status", videoPlaybackState.getPlaybackStatus());
            addLineToSB(pushInfo, "media cached percentage", videoPlaybackState.getCachedPercentage());
            addLineToSB(pushInfo, "media cached position", videoPlaybackState.getCachedPosition());
            pushInfo.append("\n");
            setResultToText(pushInfo.toString());
        }
    }

    private void addLineToSB(StringBuffer sb, String name, Object value) {
        if (sb == null) return;
        sb.
                append((name == null || "".equals(name)) ? "" : name + ": ").
                append(value == null ? "" : value + "").
                append("\n");
    }

    private void downloadFileByIndex(final int index){
        if ((mediaFileList.get(index).getMediaType() == MediaFile.MediaType.PANORAMA)
                || (mediaFileList.get(index).getMediaType() == MediaFile.MediaType.SHALLOW_FOCUS)) {
            return;
        }

        mediaFileList.get(index).fetchFileData(destDir, null, new DownloadListener<String>() {
            @Override
            public void onFailure(DJIError error) {
                HideDownloadProgressDialog();
                setResultToToast("Download File Failed" + error.getDescription());
                currentProgress = -1;
            }

            @Override
            public void onProgress(long total, long current) {
            }

            @Override
            public void onRateUpdate(long total, long current, long persize) {
                int tmpProgress = (int) (1.0 * current / total * 100);
                if (tmpProgress != currentProgress) {
                    mDownloadDialog.setProgress(tmpProgress);
                    currentProgress = tmpProgress;
                }
            }

            @Override
            public void onStart() {
                currentProgress = -1;
                ShowDownloadProgressDialog();
            }

            @Override
            public void onSuccess(String filePath) {
                HideDownloadProgressDialog();
                setResultToToast("Download File Success" + ":" + filePath);
                currentProgress = -1;
            }
        });
    }

    private void deleteFileByIndex(final int index) {
        ArrayList<MediaFile> fileToDelete = new ArrayList<MediaFile>();
        if (mediaFileList.size() > index) {
            fileToDelete.add(mediaFileList.get(index));
            mMediaManager.deleteFiles(fileToDelete, new CommonCallbacks.CompletionCallbackWithTwoParam<List<MediaFile>, DJICameraError>() {
                @Override
                public void onSuccess(List<MediaFile> x, DJICameraError y) {
                    LOGGER.e("Delete file success");
                    //DJILog.e(TAG, "Delete file success");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            MediaFile file = mediaFileList.remove(index);

                            //Reset select view
                            lastClickViewIndex = -1;
                            lastClickView = null;

                            //Update recyclerView
                            mListAdapter.notifyItemRemoved(index);
                        }
                    });
                }

                @Override
                public void onFailure(DJIError error) {
                    LOGGER.e("Delete file failed");
                   // setResultToToast("Delete file failed");
                }
            });
        }
    }

    private void playVideo() {
        mDisplayImageView.setVisibility(View.INVISIBLE);
        MediaFile selectedMediaFile = mediaFileList.get(lastClickViewIndex);
        if ((selectedMediaFile.getMediaType() == MediaFile.MediaType.MOV) || (selectedMediaFile.getMediaType() == MediaFile.MediaType.MP4)) {
            mMediaManager.playVideoMediaFile(selectedMediaFile, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (null != error) {
                        LOGGER.e("Play Video Failed" + error.getDescription());
                        //setResultToToast("Play Video Failed" + error.getDescription());
                    } else {
                        LOGGER.e("Play Video Success");
                     //   DJILog.e(TAG, "Play Video Success");
                    }
                }
            });
        }
    }

    private void moveToPosition(){

        LayoutInflater li = LayoutInflater.from(this);
        View promptsView = li.inflate(R.layout.prompt_input_position, null);
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setView(promptsView);
        final EditText userInput = (EditText) promptsView.findViewById(R.id.editTextDialogUserInput);
        alertDialogBuilder.setCancelable(false).setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                String ms = userInput.getText().toString();
                mMediaManager.moveToPosition(Integer.parseInt(ms),
                        new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError error) {
                                if (null != error) {
                                    LOGGER.e("Move to video position failed" + error.getDescription());
                                    //setResultToToast("Move to video position failed" + error.getDescription());
                                } else {
                                    LOGGER.e("Move to video position successfully.");
                                   // DJILog.e(TAG, "Move to video position successfully.");
                                }
                            }
                        });
            }
        })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();

    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.back_btn: {
                this.finish();
                break;
            }
            case R.id.delete_btn:{
                deleteFileByIndex(lastClickViewIndex);
                break;
            }
            case R.id.reload_btn: {
                getFileList();
                break;
            }
            case R.id.download_btn: {
                downloadFileByIndex(lastClickViewIndex);
                break;
            }
            case R.id.status_btn: {
                if (mPushDrawerSd.isOpened()) {
                    mPushDrawerSd.animateClose();
                } else {
                    mPushDrawerSd.animateOpen();
                }
                break;
            }
            case R.id.play_btn: {
                playVideo();
                break;
            }
            case R.id.resume_btn: {
                mMediaManager.resume(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        if (null != error) {
                            LOGGER.e("Resume Video Failed" + error.getDescription());
                           // setResultToToast("Resume Video Failed" + error.getDescription());
                        } else {
                            LOGGER.e("Resume Video Success");
                            //DJILog.e(TAG, "Resume Video Success");
                        }
                    }
                });
                break;
            }
            case R.id.pause_btn: {
                mMediaManager.pause(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        if (null != error) {
                            LOGGER.e("Pause Video Failed" + error.getDescription());
                           // setResultToToast("Pause Video Failed" + error.getDescription());
                        } else {
                            LOGGER.e("Pause Video Success");
                            //DJILog.e(TAG, "Pause Video Success");
                        }
                    }
                });
                break;
            }
            case R.id.stop_btn: {
                mMediaManager.stop(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        if (null != error) {
                            LOGGER.e("Stop Video Failed" + error.getDescription());
                           // setResultToToast("Stop Video Failed" + error.getDescription());
                        } else {
                            LOGGER.e("Stop Video Success");
                          //  DJILog.e(TAG, "Stop Video Success");
                        }
                    }
                });
                break;
            }
            case R.id.moveTo_btn: {
                moveToPosition();
                break;
            }
            default:
                break;
        }
    }

}