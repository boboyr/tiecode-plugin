package com.linux.plugin.lang.engine.highlight;

import com.linux.plugin.lang.engine.TiecodeLanguageContext;
import com.linux.plugin.lang.engine.TiecodeProvider;

import com.rapid.api.lang.engine.CancellationToken;
import com.rapid.api.lang.engine.highlight.DocumentHighlight;
import com.rapid.api.lang.engine.highlight.HighlightProvider;
import com.rapid.framework.data.document.Position;
import com.rapid.framework.data.document.Range;
import com.rapid.framework.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.tiecode.compiler.TCIDEService;
import cn.tiecode.compiler.foundation.TCPosition;
import cn.tiecode.compiler.foundation.TCRange;
import cn.tiecode.compiler.ide.TCElementKind;
import cn.tiecode.compiler.ide.TCElementTag;
import cn.tiecode.compiler.ide.TCHighlightResult;
import cn.tiecode.compiler.ide.TCSemanticHighlight;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * 结绳语义高亮:调用编译器 IDE 服务的 {@code highlight} 接口,
 * 把类、方法、变量、注解等符号按语义着色。
 */
public class TieHighlightProvider extends TiecodeProvider implements HighlightProvider {

    public TieHighlightProvider(TiecodeLanguageContext context) {
        super(context);
    }

    @Override
    public List<DocumentHighlight> documentHighlights(VirtualFile virtualFile, CancellationToken token) {
        TCIDEService service = context == null ? null : context.getIDEService();
        if (service == null || virtualFile == null) {
            return Collections.emptyList();
        }
        if (token != null && token.isCancellationRequested) {
            return Collections.emptyList();
        }
        try {
            TCHighlightResult result = service.highlight(virtualFile.asURI());
            if (result == null || result.highlights == null) {
                return Collections.emptyList();
            }
            List<DocumentHighlight> highlights = new ArrayList<>(result.highlights.size());
            for (TCSemanticHighlight item : result.highlights) {
                DocumentHighlight highlight = convert(item);
                if (highlight != null) {
                    highlights.add(highlight);
                }
            }
            return highlights;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private DocumentHighlight convert(TCSemanticHighlight item) {
        if (item == null || item.range == null) {
            return null;
        }
        DocumentHighlight highlight = new DocumentHighlight();
        highlight.range = toRange(item.range);
        highlight.color = toColor(item.kind);
        highlight.bold = hasTag(item.tags, TCElementTag.STATIC);
        highlight.strikeThrough = hasTag(item.tags, TCElementTag.DEPRECATED);
        return highlight;
    }

    private static Range toRange(TCRange tcRange) {
        Range range = new Range();
        range.start = toPosition(tcRange.start);
        range.end = toPosition(tcRange.end);
        return range;
    }

    private static Position toPosition(TCPosition tcPosition) {
        if (tcPosition == null) {
            return new Position(0, 0);
        }
        return new Position(tcPosition.line, tcPosition.column);
    }

    private static int toColor(TCElementKind kind) {
        if (kind == null) {
            return EditorColorScheme.TEXT_NORMAL;
        }
        switch (kind) {
            case CLASS:
                return EditorColorScheme.CLASS;
            case VARIABLE:
            case PARAMETER:
                return EditorColorScheme.VARIABLE;
            case COMMON_METHOD:
            case GETTER:
            case SETTER:
            case EVENT:
            case EVENT_DECLARE:
                return EditorColorScheme.FUNCTION;
            case ANNOTATION:
                return EditorColorScheme.ANNOTATION;
            default:
                return EditorColorScheme.TEXT_NORMAL;
        }
    }

    private static boolean hasTag(List<TCElementTag> tags, TCElementTag target) {
        return tags != null && tags.contains(target);
    }
}
