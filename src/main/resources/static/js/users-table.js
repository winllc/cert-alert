/* The people search table. */
(function ($) {
    'use strict';

    $(function () {
        var certState = document.getElementById('cert-state');
        var withinDays = document.getElementById('within-days');
        var applied = document.getElementById('applied-filters');

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
            var days = withinDays.value.trim();
            if (days) {
                filters.expiringWithinDays = days;
            }
            return filters;
        }

        CertAlert.initTable({
            selector: '#users-table',
            endpoint: '/api/v1/datatables/users',
            statsUrl: '/api/v1/stats/users',
            readFilters: readFilters,
            filterInputs: [certState, withinDays],
            // Sort by name, not by expiry: entries with no certificate have a null expiry,
            // and databases disagree about whether nulls sort first or last.
            order: [[1, 'asc']],
            detailUrl: function (row) {
                return '/api/v1/users/' + row.id + '/certificates';
            },
            detailSuffix: AuditTrail.placeholder('users'),
            onDetailShown: function (row, $childRow) {
                AuditTrail.attach($childRow);
            },
            entryFields: [
                ['DN', 'dn'],
                ['Employee type', 'employeeType'],
                ['Country', 'countryOfAffiliation'],
                ['Admin org', 'adminOrganization'],
                ['Telephone', 'telephoneNumber'],
                ['IC networks', 'icNetworks']
            ],
            onFiltersApplied: function (filters) {
                applied.innerHTML = CertAlert.describeFilters(filters);
            },
            columns: [
                {data: 'id', orderable: false, searchable: false, className: 'expand',
                    render: function () { return CertAlert.icon('plus'); }},
                // The name is the way through to the page about this person.
                {data: 'displayName', render: function (value, type, row) {
                    if (type !== 'display') { return value; }
                    var name = value || row.commonName || row.uid;
                    if (!name) { return CertAlert.text(null); }
                    return '<a class="fw-medium" href="/users/' + row.id + '">'
                        + CertAlert.escapeHtml(name) + '</a>';
                }},
                {data: 'uid', className: 'mono', render: renderText},
                // The link hands this person's id to the servers table, which resolves it
                // to every value a serverPOC could name them by.
                {data: 'email', render: function (value, type, row) {
                    if (type !== 'display') { return value; }
                    var label = row.displayName || row.commonName || row.uid || '';
                    var link = ' <a class="text-decoration-none ms-1" href="/servers?pocUserId=' + row.id
                        + '&pocName=' + encodeURIComponent(label)
                        + '" title="Servers this person is the contact for">'
                        + CertAlert.icon('link') + '</a>';
                    return (value ? CertAlert.escapeHtml(value) : CertAlert.text(null)) + link;
                }},
                {data: 'title', render: renderText},
                // Hidden, not dropped: still searchable, and shown in the expanded row.
                {data: 'employeeType', visible: false},
                {data: 'dutyOrganization', render: renderText},
                {data: 'countryOfAffiliation', visible: false},
                {data: 'certificateCount', searchable: false, className: 'mono text-center'},
                {data: 'certificateStatus', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.statusBadge(value) : value;
                }},
                {data: 'earliestExpiry', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.expiryCell(value) : value;
                }},
                {data: 'lastSyncedAt', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(CertAlert.formatDate(value)) : value;
                }},
                // Kept as a column so it stays searchable, but not shown: a DN is long and
                // nobody scans one. It appears in the expanded row instead.
                {data: 'dn', visible: false}
            ]
        });

        function renderText(value, type) {
            return type === 'display' ? CertAlert.text(value) : value;
        }
    });
})(jQuery);
