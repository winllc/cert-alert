package com.winllc.certalert.inventory;

import com.winllc.certalert.api.ApiTokenAuthentication;
import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.service.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's saved views: named filters for the certificate inventory. Theirs alone - nobody
 * lists, applies or deletes another's - and a person's preference rather than data, so an
 * auditor keeps views too. Saving one under a name already used replaces it.
 */
@RestController
public class SavedViewController {

    public static final String PATH = "/api/v1/saved-views";

    /** The pages a view can be for. */
    static final Set<String> PAGES = Set.of("certificates");

    static final int MAX_PER_PERSON = 100;

    private final SavedViewRepository views;
    private final Clock clock;

    public SavedViewController(SavedViewRepository views, Clock clock) {
        this.views = views;
        this.clock = clock;
    }

    public record ViewRequest(String page, String name, String query) {}

    public record ViewRow(Long id, String page, String name, String query, Instant createdAt) {

        static ViewRow of(SavedView view) {
            return new ViewRow(view.getId(), view.getPage(), view.getName(), view.getQuery(), view.getCreatedAt());
        }
    }

    @GetMapping(PATH)
    @Transactional(readOnly = true)
    public List<ViewRow> list(@RequestParam(defaultValue = "certificates") String page, Authentication authentication) {
        return views.findByOwnerAndPageOrderByNameAsc(owner(authentication), page(page)).stream().map(ViewRow::of).toList();
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ViewRow save(@RequestBody ViewRequest request, Authentication authentication) {
        String owner = owner(authentication);
        String page = page(request.page() == null ? "certificates" : request.page());
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new IllegalArgumentException("Give the view a name of up to 120 characters");
        }
        String query = request.query() == null ? "" : request.query().trim();
        // Only ever a filter: every key and value is checked before it is kept.
        CertificateFilters.parse(query);
        SavedView existing = views.findByOwnerAndPageAndName(owner, page, name).orElse(null);
        if (existing != null) {
            existing.replace(query);
            return ViewRow.of(existing);
        }
        if (views.countByOwner(owner) >= MAX_PER_PERSON) {
            throw new IllegalArgumentException("You have " + MAX_PER_PERSON + " saved views; delete one first");
        }
        return ViewRow.of(views.save(new SavedView(owner, page, name, query, Instant.now(clock))));
    }

    @DeleteMapping(PATH + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id, Authentication authentication) {
        SavedView view = views.findById(id)
                .filter(found -> found.getOwner().equals(owner(authentication)))
                // Somebody else's view is no view at all, as far as this person can tell.
                .orElseThrow(() -> new ResourceNotFoundException("No saved view " + id));
        views.delete(view);
    }

    private static String owner(Authentication authentication) {
        if (authentication == null || authentication instanceof ApiTokenAuthentication) {
            throw new AccessDeniedException("Saved views belong to people");
        }
        return AuditContext.actorOf(authentication).id();
    }

    private static String page(String page) {
        if (!PAGES.contains(page)) {
            throw new IllegalArgumentException("Views are kept for: " + String.join(", ", PAGES));
        }
        return page;
    }
}
