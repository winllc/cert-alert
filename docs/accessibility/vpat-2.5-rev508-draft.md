# CertAlert Accessibility Conformance Report

**Revised Section 508 Edition, based on VPAT® Version 2.5Rev**

> **DRAFT - SELF-ASSESSMENT FOR ENS SOLUTIONS TO REVIEW. NOT FOR RELEASE.**
> Prepared by the development team from an automated scan and an expert review
> ([wcag-2.1-aa-self-audit.md](wcag-2.1-aa-self-audit.md)). It has not been validated by
> assistive-technology testing or by a third party. ENS Solutions should complete the
> contact details, confirm each conformance level, and decide on publication.
> "VPAT" is a registered service mark of the Information Technology Industry Council (ITI).

## Name of Product/Version

CertAlert (web application), version: *to be completed at release* (this report describes
the source as of October 2026).

## Report Date

October 2026 (draft).

## Product Description

CertAlert monitors the X.509 certificates published in an LDAP directory and alerts their
owners before they expire. It is a browser-based application: administrators, auditors and
readers use it to search people, servers and certificates, manage points of contact and
projects, export the certificate inventory, review the audit trail, and configure API tokens
and webhooks. It also sends HTML and plain-text email.

## Contact Information

*ENS Solutions to complete: accessibility contact name, email, and phone.*

## Notes

- Scope: the web user interface and the HTML email listed in the self-audit. The REST API
  and OpenAPI document have no user interface. Sign-in pages of external single sign-on
  providers are the provider's.
- The classification marking (banners on every page, and in email) and its colours are set
  by the deploying organization; contrast of the banner depends on the colours chosen.
- Results reflect the default theme in current desktop Chromium, Firefox and Edge.

## Evaluation Methods Used

- Automated testing with axe-core 4.14 in Chromium (Playwright 1.56), WCAG 2.0/2.1 A and AA
  rules, on every page signed out and signed in as an administrator, run on every build in
  continuous integration; the build fails on any serious or critical violation.
- Keyboard-only checks: skip link, opening a search-table row, main tasks.
- Expert review of templates, scripts and styles against each success criterion.
- **Not yet performed:** testing with screen readers (JAWS, NVDA, VoiceOver), screen
  magnifiers, speech input, or Windows High Contrast mode; testing with users with
  disabilities.

## Applicable Standards/Guidelines

| Standard/Guideline | Included In Report |
| --- | --- |
| Web Content Accessibility Guidelines 2.0 | Level A (Yes), Level AA (Yes), Level AAA (No) |
| Web Content Accessibility Guidelines 2.1 | Level A (Yes), Level AA (Yes), Level AAA (No) |
| Revised Section 508 standards published January 18, 2017 and corrected January 22, 2018 | Yes |

## Terms

- **Supports**: the functionality of the product has at least one method that meets the
  criterion without known defects or meets with equivalent facilitation.
- **Partially Supports**: some functionality of the product does not meet the criterion.
- **Does Not Support**: the majority of product functionality does not meet the criterion.
- **Not Applicable**: the criterion is not relevant to the product.
- **Not Evaluated**: the product has not been evaluated against the criterion. This can only
  be used in WCAG Level AAA criteria.

## WCAG 2.x Report

Tables 1 and 2 also document conformance with Revised Section 508 Chapter 5 - 501.1 Scope and
504.2 Content Creation or Editing, and Chapter 6 - 602.3 Electronic Support Documentation.
When reporting on conformance with the WCAG 2.x Success Criteria, they are scoped for full
pages, complete processes, and accessibility-supported ways of using technology as documented
in the WCAG 2.0 Conformance Requirements.

### Table 1: Success Criteria, Level A

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 1.1.1 Non-text Content (Level A) | Supports | Logos have text alternatives; icons are decorative or their control is named in text. |
| 1.2.1 Audio-only and Video-only (Prerecorded) (Level A) | Not Applicable | No audio or video. |
| 1.2.2 Captions (Prerecorded) (Level A) | Not Applicable | No audio or video. |
| 1.2.3 Audio Description or Media Alternative (Prerecorded) (Level A) | Not Applicable | No audio or video. |
| 1.3.1 Info and Relationships (Level A) | Supports | Landmarks, one level-one heading per page, table header cells, labelled fields and grouped check boxes; the classification banners are text in labelled regions. Screen-reader verification pending. |
| 1.3.2 Meaningful Sequence (Level A) | Supports | Reading order follows the visual order. |
| 1.3.3 Sensory Characteristics (Level A) | Supports | |
| 1.4.1 Use of Color (Level A) | Supports | States are words; links in text are underlined. |
| 1.4.2 Audio Control (Level A) | Not Applicable | No audio. |
| 2.1.1 Keyboard (Level A) | Partially Supports | All main tasks work from the keyboard, including opening search-table rows. The suggestion lists of the contact and project pickers have not yet been verified with the keyboard. |
| 2.1.2 No Keyboard Trap (Level A) | Supports | |
| 2.1.4 Character Key Shortcuts (Level A 2.1 only) | Not Applicable | No single-key shortcuts. |
| 2.2.1 Timing Adjustable (Level A) | Supports | The idle session timeout is announced two minutes ahead with a "Stay signed in" control. The absolute session limit is a security requirement. |
| 2.2.2 Pause, Stop, Hide (Level A) | Supports | No moving or auto-updating content beyond status text. |
| 2.3.1 Three Flashes or Below Threshold (Level A) | Supports | |
| 2.4.1 Bypass Blocks (Level A) | Supports | "Skip to main content" link, landmarks and headings. |
| 2.4.2 Page Titled (Level A) | Supports | |
| 2.4.3 Focus Order (Level A) | Supports | |
| 2.4.4 Link Purpose (In Context) (Level A) | Supports | |
| 2.5.1 Pointer Gestures (Level A 2.1 only) | Supports | No path-based or multipoint gestures. |
| 2.5.2 Pointer Cancellation (Level A 2.1 only) | Supports | Activation on release (native controls). |
| 2.5.3 Label in Name (Level A 2.1 only) | Supports | |
| 2.5.4 Motion Actuation (Level A 2.1 only) | Not Applicable | No motion input. |
| 3.1.1 Language of Page (Level A) | Supports | |
| 3.2.1 On Focus (Level A) | Supports | |
| 3.2.2 On Input (Level A) | Supports | Filters update results in place, without a change of context. |
| 3.3.1 Error Identification (Level A) | Supports | |
| 3.3.2 Labels or Instructions (Level A) | Supports | |
| 4.1.1 Parsing (Level A) | Supports | Per the WCAG 2.0 and 2.1 errata (2023), always satisfied for HTML. |
| 4.1.2 Name, Role, Value (Level A) | Supports | Native controls; custom toggles expose their state. Screen-reader verification pending. |

### Table 2: Success Criteria, Level AA

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 1.2.4 Captions (Live) (Level AA) | Not Applicable | No live media. |
| 1.2.5 Audio Description (Prerecorded) (Level AA) | Not Applicable | No video. |
| 1.3.4 Orientation (Level AA 2.1 only) | Supports | |
| 1.3.5 Identify Input Purpose (Level AA 2.1 only) | Supports | Sign-in fields declare `username` and `current-password`. |
| 1.4.3 Contrast (Minimum) (Level AA) | Supports | Verified on every page by automated scan. The classification banner's colours are configured by the deploying organization. |
| 1.4.4 Resize text (Level AA) | Supports | |
| 1.4.5 Images of Text (Level AA) | Supports | The product logo only. |
| 1.4.10 Reflow (Level AA 2.1 only) | Partially Supports | Pages reflow at 320 CSS pixels; wide data tables scroll horizontally (two-dimensional content). |
| 1.4.11 Non-text Contrast (Level AA 2.1 only) | Supports | Field borders and focus indicators at 3:1 or more. |
| 1.4.12 Text Spacing (Level AA 2.1 only) | Partially Supports | The fixed-height classification banners may clip text when spacing is overridden. |
| 1.4.13 Content on Hover or Focus (Level AA 2.1 only) | Supports | |
| 2.4.5 Multiple Ways (Level AA) | Supports | |
| 2.4.6 Headings and Labels (Level AA) | Supports | |
| 2.4.7 Focus Visible (Level AA) | Supports | |
| 3.1.2 Language of Parts (Level AA) | Not Applicable | Directory content is displayed as published. |
| 3.2.3 Consistent Navigation (Level AA) | Supports | |
| 3.2.4 Consistent Identification (Level AA) | Supports | |
| 3.3.3 Error Suggestion (Level AA) | Supports | |
| 3.3.4 Error Prevention (Legal, Financial, Data) (Level AA) | Supports | Destructive actions ask for confirmation; prune and cleanup offer previews. |
| 4.1.3 Status Messages (Level AA 2.1 only) | Supports | Result counts, job status and form results are status regions. |

### Table 3: Success Criteria, Level AAA

Notes: Not evaluated.

## Revised Section 508 Report

### Chapter 3: Functional Performance Criteria (FPC)

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 302.1 Without Vision | Partially Supports | Structure, names and states are exposed to assistive technology (WCAG review and automated scan); screen-reader testing has not yet been performed. |
| 302.2 With Limited Vision | Supports | Resizable to 200%, reflows, contrast verified. |
| 302.3 Without Perception of Color | Supports | |
| 302.4 Without Hearing | Not Applicable | No audio. |
| 302.5 With Limited Hearing | Not Applicable | No audio. |
| 302.6 Without Speech | Not Applicable | No speech input. |
| 302.7 With Limited Manipulation | Partially Supports | Keyboard operable throughout except as noted under 2.1.1. |
| 302.8 With Limited Reach and Strength | Supports | |
| 302.9 With Limited Language, Cognitive, and Learning Abilities | Supports | Consistent layout and plain-language messages. |

### Chapter 4: Hardware

Notes: Not Applicable - CertAlert is software.

### Chapter 5: Software

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 501.1 Scope - Incorporation of WCAG 2.0 AA | See WCAG 2.x section | Web application. |
| 502 Interoperability with Assistive Technology | Not Applicable | A web application, not platform software; it relies on the browser's accessibility support (see the WCAG tables). |
| 503.2 User Preferences | Supports | Uses the browser's and operating system's font size and colour settings; forced-colors (High Contrast) mode not yet reviewed. |
| 503.3 Alternative User Interfaces | Not Applicable | |
| 503.4 User Controls for Captions and Audio Description | Not Applicable | No media. |
| 504.2 Content Creation or Editing | Not Applicable | CertAlert is not an authoring tool; operator-supplied email templates are outside the product's generated content. |

### Chapter 6: Support Documentation and Services

| Criteria | Conformance Level | Remarks and Explanations |
| --- | --- | --- |
| 601.1 Scope | Heading cell - no response required | |
| 602.2 Accessibility and Compatibility Features | Supports | The README's "Accessibility" section and this report describe the accessibility features. |
| 602.3 Electronic Support Documentation | See WCAG 2.x section | Documentation is Markdown rendered as HTML by the hosting service. |
| 602.4 Alternate Formats for Non-Electronic Support Documentation | Not Applicable | No non-electronic documentation. |
| 603.2 Information on Accessibility and Compatibility Features | *ENS Solutions to complete* | Depends on the support service. |
| 603.3 Accommodation of Communication Needs | *ENS Solutions to complete* | Depends on the support service. |

## Legal Disclaimer (ENS Solutions)

*ENS Solutions to complete or remove.*
