/*
 * Editing a server's points of contact, inside its expanded row.
 *
 * Two lists, and the difference between them is the whole point. What the directory
 * publishes in serverPOC is a cached copy of somebody else's data - a sweep replaces it, so
 * editing it here would last until the next one. What is added here is this application's
 * own, survives a sweep, and is what an alert about this server is actually sent to.
 *
 * A contact is a person or an address, and the two meet in the middle: typing an address
 * the directory knows links it to that person, so either route ends up filtering the same.
 */
(function (window, $) {
    'use strict';

    var SEARCH_DELAY = 250;
    var LOOKS_LIKE_ADDRESS = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

    function url(serverId, suffix) {
        return '/api/v1/servers/' + encodeURIComponent(serverId) + '/contacts' + (suffix || '');
    }

    /** The placeholder that goes into the expanded row before the contacts have loaded. */
    function placeholder(row) {
        return '<div class="contacts" data-server-id="' + CertAlert.escapeHtml(row.id) + '">'
            + '<div class="text-secondary">Loading contacts…</div></div>';
    }

    function directoryBadge(value) {
        return '<span class="badge bg-secondary-lt contact-badge" title="Published by the directory">'
            + CertAlert.escapeHtml(value) + '</span>';
    }

    function managedBadge(contact, editable) {
        var linked = contact.userId !== null && contact.userId !== undefined;
        var title = [
            linked ? 'Directory entry: ' + contact.userDn : 'Address added here',
            contact.address && contact.address !== contact.label ? 'Alerts go to ' + contact.address : null,
            contact.addedBy ? 'Added by ' + contact.addedBy : null
        ].filter(Boolean).join('\n');

        var remove = editable
            ? '<button type="button" class="btn-close ms-2 contact-remove" aria-label="Remove"'
                + ' data-contact-id="' + CertAlert.escapeHtml(contact.id) + '"></button>'
            : '';
        return '<span class="badge ' + (linked ? 'bg-blue-lt' : 'bg-azure-lt') + ' contact-badge"'
            + ' title="' + CertAlert.escapeHtml(title) + '">'
            + CertAlert.icon(linked ? 'users' : 'link', 'me-1')
            + CertAlert.escapeHtml(contact.label) + remove + '</span>';
    }

    function render($container, contacts) {
        var directory = (contacts.directory || []).map(directoryBadge).join('');
        var managed = (contacts.managed || []).map(function (contact) {
            return managedBadge(contact, contacts.editable);
        }).join('');

        var html = '<div class="contacts-heading">Points of contact</div>'
            + '<div class="contacts-group"><span class="contacts-label">From the directory</span>'
            + (directory || '<span class="text-secondary">None published</span>')
            + '</div>'
            + '<div class="contacts-group"><span class="contacts-label">Added here</span>'
            + '<span class="contacts-managed">'
            + (managed || '<span class="text-secondary">None</span>')
            + '</span></div>';

        if (contacts.editable) {
            html += '<div class="contacts-add">'
                + '<div class="contacts-search">'
                + '<input type="text" class="form-control form-control-sm contact-input" autocomplete="off"'
                + ' placeholder="Find a person, or type an email address">'
                + '<div class="contact-suggestions dropdown-menu"></div>'
                + '</div>'
                + '<button type="button" class="btn btn-sm btn-primary contact-add">Add</button>'
                + '<span class="contact-message text-secondary"></span>'
                + '</div>';
        }
        $container.html(html);
    }

    /**
     * Keeps the "+n added" badge in the row's contact column honest after an edit, without
     * reloading the table - which would close every expanded row, this one included. The
     * child row sits directly after the row it belongs to, which is how it is found.
     */
    function updateCount($container, count) {
        $container.closest('tr').prev('tr').find('.contact-count')
            .text(count > 0 ? '+' + count + ' added' : '')
            .toggleClass('d-none', count === 0);
    }

    function load($container, serverId) {
        return $.getJSON(url(serverId))
            .done(function (contacts) {
                render($container, contacts);
                updateCount($container, (contacts.managed || []).length);
                bind($container, serverId);
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                $container.html('<div class="text-secondary">Could not load the points of contact.</div>');
            });
    }

    function message($container, text, isError) {
        $container.find('.contact-message')
            .text(text || '')
            .toggleClass('text-danger', !!isError)
            .toggleClass('text-secondary', !isError);
    }

    function problem(xhr) {
        var detail = xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title);
        return detail || 'Could not add that contact.';
    }

    function add($container, serverId, body) {
        message($container, '');
        $.ajax({
            url: url(serverId),
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(body)
        })
            .done(function () {
                load($container, serverId);
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                message($container, problem(xhr), true);
            });
    }

    function bind($container, serverId) {
        var $input = $container.find('.contact-input');
        var $suggestions = $container.find('.contact-suggestions');
        var searchTimer = null;

        $container.find('.contact-remove').on('click', function () {
            var contactId = $(this).data('contact-id');
            $.ajax({url: url(serverId, '/' + contactId), type: 'DELETE'})
                .done(function () {
                    load($container, serverId);
                })
                .fail(function (xhr) {
                    if (CertAlert.handleUnauthorized(xhr)) {
                        return;
                    }
                    message($container, 'Could not remove that contact.', true);
                });
        });

        if ($input.length === 0) {
            return;
        }

        function closeSuggestions() {
            $suggestions.removeClass('show').empty();
        }

        function showSuggestions(people) {
            if (people.length === 0) {
                closeSuggestions();
                return;
            }
            $suggestions.html(people.map(function (person) {
                var subtitle = [person.email, person.dutyOrganization].filter(Boolean).join(' · ');
                return '<button type="button" class="dropdown-item contact-suggestion"'
                    + ' data-user-id="' + CertAlert.escapeHtml(person.id) + '">'
                    + CertAlert.escapeHtml(person.displayName || person.uid || person.email)
                    + (subtitle ? '<span class="text-secondary d-block small">'
                        + CertAlert.escapeHtml(subtitle) + '</span>' : '')
                    + '</button>';
            }).join('')).addClass('show');

            $suggestions.find('.contact-suggestion').on('click', function () {
                closeSuggestions();
                $input.val('');
                add($container, serverId, {userId: Number($(this).data('user-id'))});
            });
        }

        $input.on('input', function () {
            var term = $input.val().trim();
            message($container, '');
            window.clearTimeout(searchTimer);
            if (term.length < 2) {
                closeSuggestions();
                return;
            }
            searchTimer = window.setTimeout(function () {
                $.getJSON('/api/v1/users/search', {q: term})
                    .done(showSuggestions)
                    .fail(closeSuggestions);
            }, SEARCH_DELAY);
        });

        // Typing an address and pressing Add is the other half of this: a contact does not
        // have to be somebody the directory knows.
        function submit() {
            var value = $input.val().trim();
            closeSuggestions();
            if (!value) {
                return;
            }
            if (!LOOKS_LIKE_ADDRESS.test(value)) {
                message($container, 'Pick a person from the list, or type an email address.', true);
                return;
            }
            $input.val('');
            add($container, serverId, {email: value});
        }

        $container.find('.contact-add').on('click', submit);
        $input.on('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                submit();
            }
        });
        $input.on('blur', function () {
            // Long enough for a click on a suggestion to land first.
            window.setTimeout(closeSuggestions, 150);
        });
    }

    window.ServerContacts = {
        placeholder: placeholder,
        attach: function ($childRow) {
            $childRow.find('.contacts').each(function () {
                var $container = $(this);
                load($container, $container.data('server-id'));
            });
        }
    };
})(window, jQuery);
