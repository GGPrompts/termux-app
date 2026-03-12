package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * ListView adapter for terminal sessions in the PocketForge navigation drawer.
 * Displays sessions with status indicators (running/stopped/error),
 * session name, process title, and session number.
 */
public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);
        TextView sessionSubtitleView = sessionRowView.findViewById(R.id.session_subtitle);
        TextView sessionNumberView = sessionRowView.findViewById(R.id.session_number);
        View statusIndicator = sessionRowView.findViewById(R.id.session_status_indicator);

        TermuxSession termuxSession = getItem(position);
        if (termuxSession == null) {
            sessionTitleView.setText("Terminal");
            if (sessionSubtitleView != null) sessionSubtitleView.setVisibility(View.GONE);
            return sessionRowView;
        }

        TerminalSession sessionAtRow = termuxSession.getTerminalSession();
        if (sessionAtRow == null) {
            sessionTitleView.setText("Terminal");
            if (sessionSubtitleView != null) sessionSubtitleView.setVisibility(View.GONE);
            return sessionRowView;
        }

        // Session name and title
        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();
        boolean hasName = !TextUtils.isEmpty(name);
        boolean hasTitle = !TextUtils.isEmpty(sessionTitle);

        // Primary text: session name or title or default
        if (hasName) {
            sessionTitleView.setText(name);
        } else if (hasTitle) {
            sessionTitleView.setText(sessionTitle);
        } else {
            sessionTitleView.setText("Terminal");
        }

        // Subtitle: process title (if name is set and title differs)
        if (sessionSubtitleView != null) {
            if (hasName && hasTitle) {
                sessionSubtitleView.setText(sessionTitle);
                sessionSubtitleView.setVisibility(View.VISIBLE);
            } else if (!hasName && hasTitle) {
                // Title already shown as primary; show pid as subtitle
                sessionSubtitleView.setText("pid " + sessionAtRow.getPid());
                sessionSubtitleView.setVisibility(View.VISIBLE);
            } else {
                sessionSubtitleView.setText("pid " + sessionAtRow.getPid());
                sessionSubtitleView.setVisibility(View.VISIBLE);
            }
        }

        // Session number
        if (sessionNumberView != null) {
            sessionNumberView.setText("#" + (position + 1));
        }

        // Status indicator
        boolean sessionRunning = sessionAtRow.isRunning();
        if (statusIndicator != null) {
            if (sessionRunning) {
                statusIndicator.setBackgroundResource(R.drawable.pf_status_running);
            } else if (sessionAtRow.getExitStatus() == 0) {
                statusIndicator.setBackgroundResource(R.drawable.pf_status_stopped);
            } else {
                statusIndicator.setBackgroundResource(R.drawable.pf_status_error);
            }
        }

        // Text styling for running/stopped sessions
        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            sessionTitleView.setTextColor(mActivity.getColor(R.color.pf_text_primary));
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            int color = sessionAtRow.getExitStatus() == 0
                ? mActivity.getColor(R.color.pf_text_muted)
                : mActivity.getColor(R.color.pf_danger);
            sessionTitleView.setTextColor(color);
        }

        // Accessibility: set content description with session status
        String status = sessionRunning ? "running" : (sessionAtRow.getExitStatus() == 0 ? "stopped" : "error");
        sessionRowView.setContentDescription(sessionTitleView.getText() + ", " + status);

        return sessionRowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        if (clickedSession != null) {
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
            mActivity.getDrawer().closeDrawers();
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        final TermuxSession selectedSession = getItem(position);
        if (selectedSession != null) {
            mActivity.getTermuxTerminalSessionClient().renameSession(selectedSession.getTerminalSession());
        }
        return true;
    }

}
