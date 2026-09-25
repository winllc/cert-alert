/* The servers search table. */
(function ($) {
    'use strict';

    /**
     * The contacts column: one name, and what else there is.
     *
     * Two counts rather than one, because they answer different questions. "+n more" is the
     * rest of what the directory publishes; "+n added" is what somebody added here, which is
     * the only thing in this cell that is anybody's doing. Folding them together would lose
     * the distinction the expanded row is built around.
     */
    function contactsCell(display, added) {
        var published = (display || '').split(',')
            .map(function (one) { return one.trim(); })
            .filter(Boolean);

        var rest = Math.max(published.length - 1, 0);
        var html = published.length
            ? '<span class="poc-first">' + CertAlert.escapeHtml(published[0]) + '</span>'
            : '<span class="text-secondary">None published</span>';

        if (rest) {
            // A button, not a span: it does something, and the keyboard has to reach it.
            html += '<button type="button" class="badge bg-secondary-lt ms-1 poc-more"'
                + ' title="' + CertAlert.escapeHtml(published.join(", ")) + '">+'
                + rest + ' more</button>';
        }
        html += '<span class="badge bg-blue-lt ms-1 contact-count' + (added ? '' : ' d-none')
            + '">' + (added ? '+' + added + ' added' : '') + '</span>';
        return '<div class="pocs-cell">' + html + '</div>';
    }

    $(function () {
        var certState = document.getElementById('cert-state');
        var onlyMine = document.getElementById('only-mine');
        var withinDays = document.getElementById('within-days');
        var latestFrom = document.getElementById('latest-from');
        var latestTo = document.getElementById('latest-to');
        var pocInput = document.getElementById('poc-email');
        // The box under the contacts column, which searches the same thing as the one above.
        var pocColumn = document.querySelector('#servers-table tfoot input[data-filter="poc"]');
        var pocUserField = document.getElementById('poc-user-field');
        var pocUserLabel = document.getElementById('poc-user-label');
        var pocUserClear = document.getElementById('poc-user-clear');
        var riskFilter = document.getElementById('risk-filter');
        // Arriving from the metrics page with a kind of risk already chosen.
        var wantedRisk = new URLSearchParams(window.location.search).get('risk');
        if (wantedRisk) {
            riskFilter.value = wantedRisk;
        }
        // A link may want something other than the default - the metrics page sends people
        // here to see every server holding a risky name, lapsed ones among them.
        var wantedState = new URLSearchParams(window.location.search).get('certState');
        if (wantedState !== null) {
            certState.value = wantedState;
        }
        var applied = document.getElementById('applied-filters');

        // One filter, two ways in: arriving from a person's row, and the switch that names
        // the person reading the page. The id travels rather than the address, because the
        // server resolves it to every value a serverPOC could use to name them - the FSD
        // schema says that attribute carries a name, not an address.
        var params = new URLSearchParams(window.location.search);
        var myId = onlyMine ? onlyMine.getAttribute('data-user-id') : null;
        var followedUserId = params.get('pocUserId');
        var followedName = params.get('pocName');

        // Read when the filters are read rather than held in a variable of its own, so the
        // switch and the badge cannot disagree about which of them is in force.
        function contactUserId() {
            return onlyMine && onlyMine.checked ? myId : followedUserId;
        }

        /**
         * The badge names whoever the table is narrowed to, unless the switch already says
         * so: "Responsible for Alice Archer" beside a ticked "Only mine", read by Alice, is
         * the same sentence twice.
         */
        function refreshPocBadge() {
            var id = contactUserId();
            var mine = id && myId && String(id) === String(myId);
            pocUserField.hidden = !id || !!mine;
            if (id && !mine) {
                pocUserLabel.textContent = followedName || ('user #' + id);
            }
        }

        // Arriving at one's own servers by link is the switch being on, not a badge with
        // one's own name in it.
        if (onlyMine && followedUserId && myId && String(followedUserId) === String(myId)) {
            onlyMine.checked = true;
            followedUserId = null;
        }
        refreshPocBadge();
        var literalPoc = params.get('poc') || params.get('pocEmail');
        if (literalPoc) {
            pocInput.value = literalPoc;
        }

        function readFilters() {
            var filters = {};
            switch (certState.value) {
                case 'standing':
                    // Everything except what has lapsed, entries holding no certificate at
                    // all included - they have nothing expired either, and dropping them
                    // would make the default quietly narrower than it says.
                    filters.hideExpired = 'true';
                    break;
                case 'expired':
                    filters.expired = 'true';
                    break;
                case 'not-expired':
                    filters.expired = 'false';
                    break;
                case 'expiring-soon':
                    filters.certificateStatus = 'EXPIRING_SOON';
                    break;
                case 'valid':
                    filters.certificateStatus = 'VALID';
                    break;
                case 'none':
                    filters.hasCertificates = 'false';
                    break;
                default:
                    break;
            }
            var days = withinDays.value.trim();
            if (days) {
                filters.expiringWithinDays = days;
            }
            var contactUser = contactUserId();
            if (contactUser) {
                filters.pocUserId = contactUser;
            }
            if (latestFrom.value) {
                filters.latestExpiryFrom = latestFrom.value;
            }
            if (latestTo.value) {
                filters.latestExpiryTo = latestTo.value;
            }
            var contact = pocInput.value.trim() || (pocColumn ? pocColumn.value.trim() : '');
            if (contact) {
                filters.poc = contact;
            }
            if (riskFilter.value) {
                filters.risk = riskFilter.value;
            }
            var project = ProjectFilter.selected();
            if (project) {
                filters.projectId = project;
            }
            return filters;
        }

        // Filled from the projects endpoint, so the filter re-applies once it arrives:
        // arriving with ?projectId= in the URL beats the list of projects to the page.
        var projectFilter = ProjectFilter.attach(function () {
            CertAlert.reload(table, readFilters());
        });

        var table = CertAlert.initTable({
            selector: '#servers-table',
            endpoint: '/api/v1/datatables/servers',
            statsUrl: '/api/v1/stats/servers',
            readFilters: readFilters,
            filterInputs: [certState, onlyMine, withinDays, latestFrom, latestTo, pocInput, pocColumn, riskFilter,
                projectFilter],
            // Soonest to expire first, which is the order the work is in. Column 10 is
            // earliestExpiry. A server with no certificate has no expiry at all, and where
            // a NULL sorts is settled in application.yml rather than left to the database,
            // so those come last here and not at the top.
            order: [[10, 'asc']],
            detailUrl: function (row) {
                return '/api/v1/servers/' + row.id + '/certificates';
            },
            detailPrefix: ServerContacts.placeholder,
            detailSuffix: AuditTrail.placeholder('servers'),
            onDetailShown: function (row, $childRow) {
                ServerContacts.attach($childRow);
                AuditTrail.attach($childRow);
            },
            entryFields: [
                ['DN', 'dn'],
                ['URL', 'serverUrl'],
                ['IP address', 'icServerAddress'],
                ['ATO status', 'atoStatus'],
                ['Duty organization', 'dutyOrganization'],
                ['Description', 'description'],
                ['Country', 'countryOfAffiliation'],
                ['Admin org', 'adminOrganization'],
                ['IC networks', 'icNetworks']
            ],
            onFiltersApplied: function (filters) {
                applied.innerHTML = CertAlert.describeFilters(filters);
            },
            columns: [
                {data: 'id', orderable: false, searchable: false, className: 'expand',
                    render: function () { return CertAlert.icon('plus'); }},
                // The name is the way through to the page about this server.
                {data: 'commonName', render: function (value, type, row) {
                    if (type !== 'display') { return value; }
                    return value
                        ? '<a class="fw-medium" href="/servers/' + row.id + '">'
                            + CertAlert.escapeHtml(value) + '</a>'
                        : CertAlert.text(null);
                }},
                // Hidden, not dropped: still searchable, and shown in the expanded row.
                {data: 'serverUrl', visible: false},
                {data: 'icServerAddress', visible: false},
                // The first contact and a count of the rest. A server with a whole team on
                // it publishes a dozen addresses, and printing them all gave that one row
                // the width of the table and pushed the certificate columns off the side.
                // The full list is one click away in the expanded row, where it is editable
                // anyway, and hovering shows it without going anywhere.
                //
                // Not searchable as a column: it is assembled from an element collection and
                // the contacts added here, so searching it is the poc filter's job.
                {data: 'serverPocDisplay', searchable: false, className: 'pocs',
                    render: function (value, type, row) {
                        if (type !== 'display') { return value; }
                        return contactsCell(value, row.managedContactCount || 0);
                    }},
                {data: 'lifeCycleStatus', render: renderText},
                // Hidden, not dropped: still searchable, and shown in the expanded row.
                {data: 'atoStatus', visible: false},
                {data: 'dutyOrganization', visible: false},
                {data: 'certificateCount', searchable: false, className: 'mono text-center'},
                {data: 'certificateStatus', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.statusBadge(value) : value;
                }},
                {data: 'earliestExpiry', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.expiryCell(value) : value;
                }},
                // The day the last of them runs out; the same as Expires where there is one.
                {data: 'latestExpiry', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.expiryCell(value) : value;
                }},
                {data: 'lastSyncedAt', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(CertAlert.formatDate(value)) : value;
                }},
                // Searchable but not shown; it appears in the expanded row instead.
                {data: 'dn', visible: false}
            ]
        });

        // The count opens the row it belongs to, where the whole list lives. Nothing is
        // duplicated into the cell: there is one place the contacts are shown in full.
        $('#servers-table').on('click', '.poc-more', function (event) {
            event.stopPropagation();
            $(this).closest('tr').find('td.expand').trigger('click');
        });

        // The table reloads itself from filterInputs; this only keeps the badge honest.
        if (onlyMine) {
            onlyMine.addEventListener('change', refreshPocBadge);
        }

        pocUserClear.addEventListener('click', function () {
            followedUserId = null;
            pocUserField.hidden = true;
            CertAlert.reload(table, readFilters());
        });

        function renderText(value, type) {
            return type === 'display' ? CertAlert.text(value) : value;
        }
    });
})(jQuery);
