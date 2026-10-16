package com.winllc.certalert.license;

import com.enssolutions.license.LicenseCheck;

/**
 * Published when a license is installed from the license screen, with the check it left the
 * application in - for whatever has been waiting for one (the demo's first sweep).
 */
public record LicenseInstalled(LicenseCheck check) {}
