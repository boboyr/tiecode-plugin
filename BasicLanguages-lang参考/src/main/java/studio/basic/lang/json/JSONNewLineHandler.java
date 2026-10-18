package studio.basic.lang.json;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;

/**
 * Java换行处理器
 * @author Scave
 */
public class JSONNewLineHandler implements NewlineHandler {
    private int blankCount;

    @Override
    public boolean matchesRequirement(@NonNull Content text, @NonNull CharPosition position, @Nullable Styles style) {
        if (position.column < 1) {
            return false;
        }
        blankCount = 0;
        ContentLine line = text.getLine(position.line);
        char left = line.charAt(position.column - 1);
        char right = line.charAt(position.column);
        if ((left == '{' && right == '}') || left == '[' && right == ']') {
            for (int i = 0; i < position.column; i++) {
                if (Character.isWhitespace(line.charAt(i))) {
                    blankCount++;
                } else {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    @NonNull
    @Override
    public NewlineHandleResult handleNewline(@NonNull Content text, @NonNull CharPosition position, @Nullable Styles style, int tabSize) {
        String insertText = "\n" + getTab(blankCount + 4) + "\n" + getTab(blankCount);
        NewlineHandleResult result = new NewlineHandleResult(insertText, blankCount + 1);
        return result;
    }

    private String getTab(int count) {
        String result = "";
        for (int i = 0; i < count; i++) {
            result += " ";
        }
        return result;
    }
}
