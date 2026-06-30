package com.weddingarchiver.scrape;

import java.net.URI;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Rewrites url(...) references inside CSS so they point at locally-stored
 * copies. Each url(...) is resolved against the CSS file's own URI (the base
 * URI for url(...) is the stylesheet, not the HTML page that loaded it).
 *
 * Data URIs are left untouched. Failures during resolution leave the original
 * url(...) in place so an unreachable font doesn't break the whole archive.
 */
@Component
public class CssRewriter {

	private static final Pattern URL_PATTERN = Pattern.compile(
			"url\\(\\s*(['\"]?)([^'\")\\s]+)\\1\\s*\\)");

	public String rewrite(String css, URI baseUri, Function<URI, String> resolver) {
		Matcher m = URL_PATTERN.matcher(css);
		StringBuilder sb = new StringBuilder(css.length());
		while (m.find()) {
			String original = m.group(2);
			if (original.isBlank()
					|| original.startsWith("data:")
					|| original.startsWith("#")) {
				m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
				continue;
			}
			try {
				URI resolved = baseUri.resolve(original);
				String localPath = resolver.apply(resolved);
				if (localPath == null) {
					m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
				} else {
					m.appendReplacement(sb,
							Matcher.quoteReplacement("url(\"" + localPath + "\")"));
				}
			} catch (RuntimeException e) {
				m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
			}
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
