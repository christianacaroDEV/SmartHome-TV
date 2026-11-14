package com.liskovsoft.smartyoutubetv2.common.misc.together;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * "Watch together" on the TV (remote control): join with the code, react, leave.
 * Messages and voice notes from the others show up / play by themselves (see {@link TogetherManager}).
 */
public final class TogetherTvDialog {
    private static final String[] REACTIONS = {"❤️", "😂", "😮", "👏"};

    private TogetherTvDialog() {
    }

    public static void show(Context context) {
        TogetherManager manager = TogetherManager.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        String title = context.getString(R.string.together_title);

        if (manager.isActive()) {
            String info = manager.describeRoom(context);

            List<OptionItem> reactions = new ArrayList<>();
            for (String reaction : REACTIONS) {
                reactions.add(UiOptionItem.from(reaction, option -> sent(context, manager, reaction)));
            }
            for (int resId : new int[] {R.string.together_quick_1, R.string.together_quick_2, R.string.together_quick_3}) {
                String text = context.getString(resId);
                reactions.add(UiOptionItem.from(text, option -> sent(context, manager, text)));
            }
            dialog.appendStringsCategory(context.getString(R.string.together_send_reaction), reactions);

            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.together_leave), option -> {
                manager.leave();
                dialog.closeDialog();
                refreshSection(context);
            }));

            dialog.showDialog(info);
            return;
        }

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.together_join_room), option -> {
            dialog.closeDialog();
            SimpleEditDialog.show(context, context.getString(R.string.together_join_room), context.getString(R.string.together_code_hint), "", newValue -> {
                manager.join(newValue, success -> {
                    MessageHelpers.showMessage(context,
                            success ? context.getString(R.string.together_title) + " ✓" : context.getString(R.string.together_join_failed));
                    refreshSection(context);
                });
                return true;
            });
        }));

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.together_create), option -> {
            dialog.closeDialog();
            manager.create(success -> {
                refreshSection(context);
                if (success) {
                    String code = manager.getCode();
                    MessageHelpers.showMessage(context, context.getString(R.string.together_created) + " "
                            + code.substring(0, 3) + " " + code.substring(3), true);
                } else {
                    MessageHelpers.showMessage(context, R.string.together_create_failed);
                }
            });
        }));

        dialog.showDialog(title);
    }

    private static void refreshSection(Context context) {
        com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter.instance(context).refresh(false);
    }

    private static void sent(Context context, TogetherManager manager, String text) {
        manager.sendText(text);
        MessageHelpers.showMessage(context, "✓ " + text);
    }
}
