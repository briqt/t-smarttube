package com.liskovsoft.smartyoutubetv2.tv.ui.dialogs;

import android.app.Activity;
import androidx.preference.Preference;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class AppPreferenceManagerTest {
    @Test public void aBoundButtonUpdatesItsTitleWithoutSelectingItOrReplacingThePreference() {
        ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class);
        owner.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat); owner.setup();
        try {
            int[] selected = {0};
            UiOptionItem item = (UiOptionItem) UiOptionItem.from("Before save", option -> selected[0]++);
            Preference preference = new AppPreferenceManager(owner.get()).createPreference(OptionCategory.singleButton(item));
            assertEquals("Before save", preference.getTitle());
            String key = preference.getKey();
            item.setTitle("Saving");
            assertEquals("Saving", preference.getTitle());
            item.setTitle("Saved");
            assertEquals("Saved", preference.getTitle());
            assertEquals(key, preference.getKey());
            assertEquals(0, selected[0]);
            item.setTitle("Connection\nThe test Key is kept in memory only");
            assertFalse("A multiline status must not be clipped to a single line", preference.isSingleLineTitle());
            assertEquals(item.getTitle(), preference.getTitle());
            assertTrue(preference.getOnPreferenceClickListener().onPreferenceClick(preference));
            assertEquals(1, selected[0]);
            UiOptionItem other = (UiOptionItem) UiOptionItem.from("Another page");
            Preference otherPreference = new AppPreferenceManager(owner.get()).createPreference(OptionCategory.singleButton(other));
            item.setTitle("Late save result");
            assertEquals("Another page", otherPreference.getTitle());
        } finally { owner.pause().stop().destroy(); }
    }
}
