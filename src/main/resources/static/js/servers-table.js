/* The servers search table. */
(function ($) {
    'use strict';

    $(function () {
        var certState = document.getElementById('cert-state');
        var showExpired = document.getElementById('show-expired');
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
        var applied = document.getElementById('applied-filters');

        // Arriving from a person's row filters by them. The id travels rather than the
        // address, because the server resolves it to every value a serverPOC could use to
        // name them - the FSD schema says that attribute carries a name, not an address.
        var params = new URLSearchParams(window.location.search);
        var pocUserId = params.get('pocUserId');
        if (pocUserId) {
            pocUserLabel.textContent = params.get('pocName') || ('user #' + pocUserId);
            pocUserField.hidden = false;
        }
        var literalPoc = params.get('poc') || params.get('pocEmail');
        if (literalPoc) {
            pocInput.value = literalPoc;
        }

        function readFilters() {
            var filters = {};
            switch (certState.value) {
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
            // Asking for expired entries outright overrides hiding them.
            var askedForExpired = certState.value === 'expired';
            showExpired.disabled = askedForExpired;
            if (!showExpired.checked && !askedForExpired) {
                filters.hideExpired = 'true';
            }
            var days = withinDays.value.trim();
            if (days) {
                filters.expiringWithinDays = days;
            }
            if (pocUserId) {
                filters.pocUserId = pocUserId;
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
            filterInputs: [certState, showExpired, withinDays, latestFrom, latestTo, pocInput, pocColumn, riskFilter, projectFilter],
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
                // What the directory publishes, plus a count of what was added here. The
                // editable list is in the expanded row; this is only the signal that it
                // has something in it.
                //
                // Not searchable as a column: it is assembled from an element collection and
                // the contacts added here, so searching it is the poc filter's job.
                {data: 'serverPocDisplay', searchable: false, render: function (value, type, row) {
                    if (type !== 'display') { return value; }
                    var count = row.managedContactCount || 0;
                    return CertAlert.text(value)
                        + '<span class="badge bg-blue-lt ms-1 contact-count' + (count ? '' : ' d-none')
                        + '">' + (count ? '+' + count + ' added' : '') + '</span>';
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

        pocUserClear.addEventListener('click', function () {
            pocUserId = null;
            pocUserField.hidden = true;
            CertAlert.reload(table, readFilters());
        });

        function renderText(value, type) {
            return type === 'display' ? CertAlert.text(value) : value;
        }
    });
})(jQuery);
