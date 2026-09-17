package com.winllc.certalert.web.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A page of anything.
 *
 * <p>Spring's own {@code Page} serialises to a shape its documentation warns may change, so
 * the wire format is stated here instead and the browser can rely on it.
 *
 * @param content the rows on this page
 * @param page zero-based page number
 * @param size how many rows a page holds
 * @param totalElements how many rows there are altogether
 * @param totalPages how many pages that makes
 * @param first whether this is the first page
 * @param last whether this is the last
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
