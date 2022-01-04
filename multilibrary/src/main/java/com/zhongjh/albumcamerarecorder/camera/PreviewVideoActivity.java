package com.zhongjh.albumcamerarecorder.camera;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.VideoView;

import com.zhongjh.albumcamerarecorder.R;
import com.zhongjh.albumcamerarecorder.camera.util.FileUtil;
import com.zhongjh.albumcamerarecorder.settings.CameraSpec;
import com.zhongjh.albumcamerarecorder.settings.GlobalSpec;
import com.zhongjh.albumcamerarecorder.utils.BitmapUtils;
import com.zhongjh.albumcamerarecorder.widget.progressbutton.CircularProgressButton;

import java.io.File;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.zhongjh.common.entity.LocalFile;
import com.zhongjh.common.enums.MimeType;
import com.zhongjh.common.enums.MultimediaTypes;
import com.zhongjh.common.listener.VideoEditListener;
import com.zhongjh.common.utils.MediaStoreCompat;
import com.zhongjh.common.utils.StatusBarUtils;
import com.zhongjh.common.utils.ThreadUtils;

import org.jetbrains.annotations.NotNull;

import static com.zhongjh.albumcamerarecorder.camera.constants.CameraTypes.TYPE_VIDEO;

/**
 * 一个预览合成分段录制的视频
 *
 * @author zhongjh
 */
public class PreviewVideoActivity extends AppCompatActivity {

    private static final String TAG = PreviewVideoActivity.class.getSimpleName();
    /**
     * 合成视频录制的预览
     */
    public static final int REQUEST_CODE_PREVIEW_VIDEO = 26;
    static final String LOCAL_FILE = "LOCAL_FILE";
    static final String PATH = "PATH";

    VideoView mVideoViewPreview;
    ImageView mImgClose;
    CircularProgressButton mBtnConfirm;
    /**
     * 该视频的相关参数
     */
    LocalFile mLocalFile = new LocalFile();
    /**
     * 按钮事件运行中，因为该自定义控件如果通过setEnabled控制会导致动画不起效果，所以需要该变量控制按钮事件是否生效
     */
    boolean mIsRun;

    /**
     * 录像文件配置路径
     */
    private MediaStoreCompat mVideoMediaStoreCompat;
    /**
     * 拍摄配置
     */
    private CameraSpec mCameraSpec;

    /**
     * 打开activity
     *
     * @param fragment 打开者
     * @param path     视频地址
     */
    public static void startActivity(Fragment fragment, String path) {
        Intent intent = new Intent();
        intent.putExtra(PATH, path);
        intent.setClass(fragment.getContext(), PreviewVideoActivity.class);
        fragment.startActivityForResult(intent, REQUEST_CODE_PREVIEW_VIDEO);
        fragment.getActivity().overridePendingTransition(R.anim.activity_open, 0);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setRequestedOrientation(GlobalSpec.getInstance().orientation);
        StatusBarUtils.initStatusBar(PreviewVideoActivity.this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview_video);
        mLocalFile.setPath(getIntent().getStringExtra(PATH));
        // 初始化设置
        mCameraSpec = CameraSpec.getInstance();
        initView();
        initListener();
        initData();
    }

    @Override
    public void finish() {
        super.finish();
        //关闭窗体动画显示
        this.overridePendingTransition(0, R.anim.activity_close);
    }

    @Override
    protected void onDestroy() {
        if (mCameraSpec.videoEditCoordinator != null) {
            mCameraSpec.videoEditCoordinator.onCompressDestroy();
        }
        super.onDestroy();
    }

    /**
     * 初始化View
     */
    private void initView() {
        mVideoViewPreview = findViewById(R.id.vvPreview);
        mImgClose = findViewById(R.id.imgClose);
        mBtnConfirm = findViewById(R.id.btnConfirm);
        mBtnConfirm.setIndeterminateProgressMode(true);
    }

    private void initListener() {
        mBtnConfirm.setOnClickListener(v -> {
            if (mIsRun) {
                return;
            }
            mIsRun = true;
            confirm();
        });
        mImgClose.setOnClickListener(v -> PreviewVideoActivity.this.finish());
    }

    /**
     * 初始化数据
     */
    private void initData() {
        // 公共配置
        GlobalSpec mGlobalSpec = GlobalSpec.getInstance();
        mVideoMediaStoreCompat = new MediaStoreCompat(PreviewVideoActivity.this,
                mGlobalSpec.videoStrategy == null ? mGlobalSpec.saveStrategy : mGlobalSpec.videoStrategy);

        File file = new File(mLocalFile.getPath());
        playVideo(file);
    }

    /**
     * 播放视频,用于录制后，在是否确认的界面中，播放视频
     */
    private void playVideo(File file) {
        mVideoViewPreview.pause();
        // mediaController 是底部控制条
        MediaController mediaController = new MediaController(PreviewVideoActivity.this);
        mediaController.setAnchorView(mVideoViewPreview);
        mediaController.setMediaPlayer(mVideoViewPreview);
        mediaController.setVisibility(View.GONE);
        mVideoViewPreview.setMediaController(mediaController);
        Uri uri = Uri.fromFile(file);
        mVideoViewPreview.setVideoURI(uri);
        // 这段代码需要放在更新视频文件后播放，不然会找不到文件。
        mVideoViewPreview.setVisibility(View.VISIBLE);
        if (!mVideoViewPreview.isPlaying()) {
            mVideoViewPreview.start();
        }
        mVideoViewPreview.setOnPreparedListener(mp -> {
            // 获取相关参数
            mLocalFile.setDuration(mVideoViewPreview.getDuration());
            mLocalFile.setWidth(mVideoViewPreview.getWidth());
            mLocalFile.setHeight(mVideoViewPreview.getHeight());
        });
        mVideoViewPreview.setOnCompletionListener(mediaPlayer -> {
            // 循环播放
            if (!mVideoViewPreview.isPlaying()) {
                mVideoViewPreview.start();
            }
        });
    }

    /**
     * 提交
     */
    private void confirm() {
        // 判断是否开启了视频编辑功能
        if (mCameraSpec.videoEditCoordinator != null) {
            // 如果开启了直接压缩
            compress();
        } else {
            // 否则直接转移
            moveVideoFile();
        }
    }

    /**
     * 压缩视频
     */
    private void compress() {
        // 获取文件名称
        String newFileName = mLocalFile.getPath().substring(mLocalFile.getPath().lastIndexOf(File.separator));
        File newFile = mVideoMediaStoreCompat.createFile(newFileName, 1, false);
        mCameraSpec.videoEditCoordinator.setVideoCompressListener(new VideoEditListener() {
            @Override
            public void onFinish() {
                confirm(newFile);
            }

            @Override
            public void onProgress(int progress, long progressTime) {
                mBtnConfirm.setProgress(progress);
            }

            @Override
            public void onCancel() {

            }

            @Override
            public void onError(@NotNull String message) {
                mIsRun = false;
            }
        });
        mCameraSpec.videoEditCoordinator.compress(mLocalFile.getPath(), newFile.getPath());
    }

    /**
     * 迁移视频文件，缓存文件迁移到配置目录
     */
    private void moveVideoFile() {
        Log.d(TAG, "moveVideoFile");
        // 执行等待动画
        mBtnConfirm.setProgress(50);
        // 开始迁移文件，将 缓存文件 拷贝到 配置目录
        ThreadUtils.executeByIo(new ThreadUtils.BaseSimpleBaseTask<Void>() {
            @Override
            public Void doInBackground() {
                // 获取文件名称
                String newFileName = mLocalFile.getPath().substring(mLocalFile.getPath().lastIndexOf(File.separator));
                File newFile = mVideoMediaStoreCompat.createFile(newFileName, 1, false);
                FileUtil.copy(new File(mLocalFile.getPath()), newFile, null, (ioProgress, file) -> {
                    if (ioProgress >= 1) {
                        ThreadUtils.runOnUiThread(() -> {
                            mBtnConfirm.setProgress(100);
                            mIsRun = false;
                            confirm(newFile);
                        });
                    }
                });
                return null;
            }

            @Override
            public void onSuccess(Void result) {

            }

            @Override
            public void onFail(Throwable t) {
                super.onFail(t);
                mIsRun = false;
            }
        });
    }

    /**
     * 确定该视频
     */
    private void confirm(File newFile) {
        Intent intent = new Intent();
        Uri mediaUri = BitmapUtils.displayToGallery(getApplicationContext(), newFile, TYPE_VIDEO, mLocalFile.getDuration(),
                mLocalFile.getWidth(), mLocalFile.getHeight(),
                mVideoMediaStoreCompat.getSaveStrategy().getDirectory(), mVideoMediaStoreCompat);
        mLocalFile.setPath(newFile.getPath());
        mLocalFile.setUri(mediaUri);
        mLocalFile.setSize(newFile.length());
        mLocalFile.setMimeType(MimeType.MP4.getMMimeTypeName());
        mLocalFile.setType(MultimediaTypes.VIDEO);
        intent.putExtra(LOCAL_FILE, mLocalFile);
        setResult(RESULT_OK, intent);
        PreviewVideoActivity.this.finish();
    }

}
