/*
 * The addresses a person answers to, on their details page.
 *
 * The directory's own are read-only: a sweep replaces them. The ones added here are what
 * binds a person to a server whose serverPOC is an address the directory never published -
 * an old one, a role address, or the team's list. A list is expected to belong to several
 * people at once, and the badge says so.
 */
(function (window, $) {
    'use strict';

    var LOOKS_LIKE_ADDRESS = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

    function url(userId, suffix) {
        return '/api/v1/users/' + encodeURIComponent(userId) + '/addresses' + (suffix || '');
    }

    function publishedBadge(address) {
        return '<span class="badge bg-secondary-lt contact-badge" title="Published by the directory">'
            + CertAlert.escapeHtml(address) + '</span>';
    }

    function addedBadge(alias, editable) {
        var group = alias.kind === 'GROUP';
        var title = [
            group ? 'A group address' : 'Added here',
            alias.label,
            alias.sharedWith > 0
                ? 'Also answered to by ' + alias.sharedWith + ' other ' + (alias.sharedWith === 1 ? 'person' : 'people')
                : null,
            alias.addedBy ? 'Added by ' + alias.addedBy : null
        ].filter(Boolean).join('\n');

        var remove = editable
            ? '<button type="button" class="btn-close ms-2 address-remove" aria-label="Remove"'
                + ' data-alias-id="' + CertAlert.escapeHtml(alias.id) + '"></button>'
            : '';
        return '<span class="badge ' + (group ? 'bg-purple-lt' : 'bg-azure-lt') + ' contact-badge"'
            + ' title="' + CertAlert.escapeHtml(title) + '">'
            + CertAlert.icon(group ? 'users' : 'link', 'me-1')
            + CertAlert.escapeHtml(alias.address)
            + (group ? '<span class="ms-1 text-secondary">group</span>' : '')
            + (alias.sharedWith > 0 ? '<span class="ms-1 text-secondary">+' + alias.sharedWith + '</span>' : '')
            + remove + '</span>';
    }

    function render($container, addresses) {
        var published = (addresses.directory || []).map(publishedBadge).join('');
        var added = (addresses.added || []).map(function (alias) {
            return addedBadge(alias, addresses.editable);
        }).join('');

        var html = '<div class="contacts-group"><span class="contacts-label">From the directory</span>'
            + (published || '<span class="text-secondary">None published</span>')
            + '</div>'
            + '<div class="contacts-group"><span class="contacts-label">Added here</span>'
            + '<span class="addresses-added">'
            + (added || '<span class="text-secondary">None</span>')
            + '</span></div>';

        if (addresses.editable) {
            html += '<div class="contacts-add">'
                + '<input type="text" class="form-control form-control-sm address-input" autocomplete="off"'
                + ' placeholder="another address, or a team list" style="max-width: 20rem">'
                + '<label class="form-check form-check-inline form-switch mb-0">'
                + '<input class="form-check-input address-group" type="checkbox">'
                + '<span class="form-check-label">Group address</span>'
                + '</label>'
                + '<button type="button" class="btn btn-sm btn-primary address-add">Add</button>'
                + '<span class="address-message text-secondary"></span>'
                + '</div>';
        }
        $container.html(html);
    }

    function message($container, text, isError) {
        $container.find('.address-message')
            .text(text || '')
            .toggleClass('text-danger', !!isError)
            .toggleClass('text-secondary', !isError);
    }

    function load($container) {
        var userId = $container.data('user-id');
        $.getJSON(url(userId))
            .done(function (addresses) {
                render($container, addresses);
                bind($container, userId);
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                $container.html('<div class="text-secondary">Could not load the addresses.</div>');
            });
    }

    function bind($container, userId) {
        $container.find('.address-remove').on('click', function () {
            $.ajax({url: url(userId, '/' + $(this).data('alias-id')), type: 'DELETE'})
                .done(function () {
                    load($container);
                })
                .fail(function (xhr) {
                    if (CertAlert.handleUnauthorized(xhr)) {
                        return;
                    }
                    message($container, 'Could not remove that address.', true);
                });
        });

        var $input = $container.find('.address-input');
        if ($input.length === 0) {
            return;
        }

        function submit() {
            var address = $input.val().trim();
            if (!address) {
                return;
            }
            if (!LOOKS_LIKE_ADDRESS.test(address)) {
                message($container, 'That does not look like an email address.', true);
                return;
            }
            message($container, '');
            $.ajax({
                url: url(userId),
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    address: address,
                    kind: $container.find('.address-group').is(':checked') ? 'GROUP' : 'PERSONAL'
                })
            })
                .done(function () {
                    $input.val('');
                    load($container);
                })
                .fail(function (xhr) {
                    if (CertAlert.handleUnauthorized(xhr)) {
                        return;
                    }
                    var detail = xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title);
                    message($container, detail || 'Could not add that address.', true);
                });
        }

        $container.find('.address-add').on('click', submit);
        $input.on('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                submit();
            }
        });
    }

    $(function () {
        $('.addresses').each(function () {
            load($(this));
        });
    });
})(window, jQuery);
