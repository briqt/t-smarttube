package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.subtitle.settings.CaptionSettingsController;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

public class SubtitleSettingsPresenter extends BasePresenter<Void> {
    private final PlayerData mPlayerData;

    public SubtitleSettingsPresenter(Context context) {
        super(context);
        mPlayerData = PlayerData.instance(context);
    }

    public static SubtitleSettingsPresenter instance(Context context) {
        return new SubtitleSettingsPresenter(context);
    }

    public void show() {
        CaptionSettingsController captions = getPlaybackPresenter().getController(CaptionSettingsController.class);
        if (captions != null && !captions.isReady()) {
            Class<?> originView = getViewManager().getTopView();
            boolean wasDialogShown = AppDialogPresenter.instance(getContext()).isDialogShown();
            captions.whenReady(() -> {
                if (getViewManager().getTopView() != originView ||
                        (!wasDialogShown && AppDialogPresenter.instance(getContext()).isDialogShown())) {
                    return;
                }
                showInternal(captions);
            });
        } else {
            showInternal(captions);
        }
    }

    private void showInternal(CaptionSettingsController captions) {
        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        if (captions != null) {
            captions.appendGlobalSettings(settingsPresenter);
        }
        settingsPresenter.appendSingleSwitch(AppDialogUtil.createSubtitleChannelOption(getContext()));
        // Can't work properly. There is no robust language detection.
        //appendSubtitleLanguageCategory(settingsPresenter);
        //appendMoreSubtitlesSwitch(settingsPresenter);
        appendSubtitleStyleCategory(settingsPresenter);
        appendSubtitleSizeCategory(settingsPresenter);
        appendSubtitlePositionCategory(settingsPresenter);

        settingsPresenter.showDialog(getContext().getString(R.string.subtitle_category_title),
                captions == null ? null : captions::onStandaloneClosed);
    }

    //private void appendSubtitleLanguageCategory(AppSettingsPresenter settingsPresenter) {
    //    String subtitleLanguageTitle = getContext().getString(R.string.subtitle_language);
    //    String subtitlesDisabled = getContext().getString(R.string.subtitles_disabled);
    //
    //    LangUpdater langUpdater = new LangUpdater(getContext());
    //    Map<String, String> locales = langUpdater.getSupportedLocales();
    //    FormatItem currentFormat = mPlayerData.getFormat(FormatItem.TYPE_SUBTITLE);
    //
    //    List<OptionItem> options = new ArrayList<>();
    //
    //    options.add(UiOptionItem.from(
    //            subtitlesDisabled, option -> mPlayerData.setFormat(FormatItem.fromLanguage(null)),
    //            currentFormat == null || currentFormat.equals(FormatItem.fromLanguage(null))));
    //
    //    for (Entry<String, String> entry : locales.entrySet()) {
    //        if (entry.getValue().isEmpty()) {
    //            // Remove default language entry
    //            continue;
    //        }
    //
    //        options.add(UiOptionItem.from(
    //                entry.getKey(), option -> mPlayerData.setFormat(FormatItem.fromLanguage(entry.getValue())),
    //                FormatItem.fromLanguage(entry.getValue()).equals(currentFormat)));
    //    }
    //
    //    settingsPresenter.appendRadioCategory(subtitleLanguageTitle, options);
    //}

    private void appendSubtitleStyleCategory(AppDialogPresenter settingsPresenter) {
        OptionCategory category = AppDialogUtil.createSubtitleStylesCategory(getContext());
        settingsPresenter.appendRadioCategory(category.title, category.options);
    }

    private void appendSubtitleSizeCategory(AppDialogPresenter settingsPresenter) {
        OptionCategory category = AppDialogUtil.createSubtitleSizeCategory(getContext());
        settingsPresenter.appendRadioCategory(category.title, category.options);
    }

    private void appendSubtitlePositionCategory(AppDialogPresenter settingsPresenter) {
        OptionCategory category = AppDialogUtil.createSubtitlePositionCategory(getContext());
        settingsPresenter.appendRadioCategory(category.title, category.options);
    }

    private void appendMoreSubtitlesSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from("Unlock more subtitles",
                option -> MediaServiceData.instance().setMoreSubtitlesUnlocked(option.isSelected()),
                MediaServiceData.instance().isMoreSubtitlesUnlocked()));
    }
}
