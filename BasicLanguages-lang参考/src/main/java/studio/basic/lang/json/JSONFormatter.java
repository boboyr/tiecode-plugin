package studio.basic.lang.json;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.TextRange;

public class JSONFormatter implements Formatter {
    private boolean isRunning = false;
    private FormatResultReceiver receiver;

    @Override
    public void format(@NonNull Content text, @NonNull TextRange cursorRange) {
        isRunning = true;
        try {
            JSONObject object = new JSONObject(String.valueOf(text));
            String formatted = object.toString(4);
            receiver.onFormatSucceed(formatted, null);
        } catch (JSONException e) {
            receiver.onFormatFail(e);
        }
        isRunning = false;
    }

    @Override
    public void formatRegion(@NonNull Content text, @NonNull TextRange rangeToFormat, @NonNull TextRange cursorRange) {

    }

    @Override
    public void setReceiver(@Nullable FormatResultReceiver receiver) {
        this.receiver = receiver;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void destroy() {

    }
}
