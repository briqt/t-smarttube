package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridge;

public interface PlaybackView extends PlayerManager {
    void showProgressBar(boolean show);

    default CaptionOutputBridge getCaptionOutputBridge() {
        return null;
    }
}
