package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class CategoryEmptyError implements ErrorFragmentData {
    private final Context mContext;
    private final Throwable mError;

    public CategoryEmptyError(Context context, @Nullable Throwable error) {
        mContext = context;
        mError = error;
    }

    /**
     * Connection hiccup (DNS not ready after waking up, wifi/data switch, timeout): retrying a moment later works,
     * so it gets a friendly message and a Retry button instead of the stack trace.
     */
    public static boolean isNetworkError(@Nullable Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                    || e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException
                    || e instanceof javax.net.ssl.SSLException || e instanceof java.net.SocketException) {
                return true;
            }
            if (Helpers.containsAny(e.getMessage(), "UnknownHostException", "Unable to resolve host", "SocketTimeoutException",
                    "ConnectException", "Failed to connect", "timeout", "Connection reset", "SSLHandshakeException")) {
                return true;
            }
        }
        return false;
    }

    private boolean isNetworkError() {
        return isNetworkError(mError);
    }

    @Override
    public void onAction() {
        if (isNetworkError()) {
            com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter.instance(mContext).refresh(false);
            return;
        }
        YTSignInPresenter.instance(mContext).start();
    }

    @Override
    public String getMessage() {
        if (isNetworkError()) {
            return mContext.getString(R.string.msg_network_hiccup);
        }
        String result = mContext.getString(R.string.msg_cant_load_content);
        if (mError != null && !Helpers.containsAny(mError.getMessage(), "fromNullable result is null")) {
            String className = mError.getClass().getSimpleName();
            result = String.format("%s: %s", className, Utils.getStackTraceAsString(mError));
            //result = mError.getMessage();
        }
        return result;
    }

    @Override
    public String getActionText() {
        if (isNetworkError()) {
            return mContext.getString(R.string.offline_retry);
        }
        return mError != null && Helpers.startsWith(mError.getMessage(), "AuthError") ? mContext.getString(R.string.action_signin) : null;
    }
}
