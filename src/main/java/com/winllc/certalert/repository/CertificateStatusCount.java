package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CertificateStatus;

/** One row of a certificate roll-up: how many entries are in this state. */
public interface CertificateStatusCount {

    CertificateStatus getStatus();

    long getTotal();
}
