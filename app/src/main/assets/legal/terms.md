TERMS AND CONDITIONS

Last updated: [effective date]

These Terms and Conditions ("Terms") govern your access to and use of the
Gotcha mobile application ("App") and related services provided by
Gotcha ("we," "us," or "our"). By installing, accessing, or otherwise
using the App, you agree to be bound by these Terms. If you do not agree,
do not install or use the App.

1. ELIGIBILITY

   1.1 You must be at least 18 years of age (or the age of digital consent
       in your jurisdiction, whichever is higher) to use the App.
   1.2 By using the App, you represent and warrant that you meet the age
       requirement and have the legal capacity to enter into these Terms.
   1.3 If you are using the App on behalf of an organization, you further
       represent that you have authority to bind that organization.

2. THE SERVICE

   2.1 Gotcha is an on-device AI assistant. The App itself does not require
       a network connection; the user chooses the AI backend.
   2.2 The App supports two classes of backends:
         (a) "Samosa AI" — a managed AI service accessed by signing in
             through Google and routing requests through Samosa AI's
             proxy endpoint ("Samosa AIR"); and
         (b) any "User-Provided Provider" — an OpenAI-compatible endpoint
             configured by you (your own key, your own endpoint).
   2.3 The user is responsible for selecting which backend to use and for
       the consequences of that choice (cost, data handling, accuracy).

3. LICENSE

   3.1 Subject to your compliance with these Terms, we grant you a limited,
       non-exclusive, non-transferable, revocable license to install and
       use the App on devices you own or control.
   3.2 You may not: (a) copy, modify, or distribute the App; (b) reverse
       engineer it except to the extent applicable law expressly permits;
       (c) rent, lease, sell, sublicense, or transfer it; (d) use it to
       build a competing product; or (e) remove or alter proprietary notices.

4. ACCOUNTS, CREDENTIALS, AND API KEYS

   4.1 When you sign in to Samosa AI, your session is authenticated through
       Google and bound to a short-lived token managed by us. We do not
       see or store your Google password.
   4.2 When you bring your own provider, the App stores your base URL and
       API key locally on your device using Android's encrypted SharedPreferences.
       The key never leaves your device except as a request header to the
       endpoint you configured.
   4.3 You are solely responsible for the security of any credentials you
       enter into the App, for any endpoint you point the App at, and for
       any access or cost incurred under your account.

5. ACCEPTABLE USE

   5.1 You agree to use the App only for lawful purposes and in a manner
       that does not infringe the rights of, restrict, or inhibit anyone
       else's use and enjoyment of the App.
   5.2 You agree NOT to use the App (directly or via the AI agent it
       controls) to:
         (a) violate any applicable law, regulation, or third-party right;
         (b) generate, distribute, or act on content that is illegal,
             infringing, defamatory, harassing, hateful, sexually
             exploitative of minors, or otherwise harmful;
         (c) target, harm, or attempt to gain unauthorized access to any
             system, account, network, or device;
         (d) send unsolicited communications, spam, or phishing;
         (e) produce or distribute malware, ransomware, or other malicious
             code;
         (f) interfere with or disrupt the App, its backends, or any
             network or system used to provide them;
         (g) conduct fraud, money laundering, market manipulation, or
             other deceptive practices;
         (h) reverse-engineer prompts, model weights, or other proprietary
             AI assets belonging to us or to any backend provider;
         (i) bypass any rate limits, safety filters, or paywalls; or
         (j) attempt any of the above or assist anyone else to do so.

6. THE AI ASSISTANT — IMPORTANT DISCLAIMER

   6.1 The App uses a large language model that can produce incorrect,
       incomplete, misleading, or outdated output ("AI Output"). AI Output
       is provided for informational purposes only and is not professional
       advice (legal, medical, financial, or otherwise).
   6.2 You are responsible for verifying AI Output before relying on it.
       Treat AI Output as a draft, not as a fact.
   6.3 The AI agent is granted broad on-device capabilities through the
       Android permissions you grant it (Accessibility, Device Admin, Media
       Projection, Notifications, Storage, SMS, Calls, etc.). When those
       permissions are granted, the agent can perform real, irreversible
       actions on your device: send messages, place calls, uninstall apps,
       delete files, modify system settings, set passwords, lock the
       screen, format storage, and more.
   6.4 You are solely responsible for granting those permissions, for the
       instructions you give the agent, and for any consequences that
       follow — including accidental data loss, unintended purchases,
       messages sent in your name, settings changed, or device damage.

7. DEVICE INTEGRITY AND DATA LOSS

   7.1 The App and its AI agent are designed to operate within the bounds
       of standard Android permission systems. Nonetheless, software may
       fail; an AI may misunderstand; a permission may be misused; a
       network or service may misbehave.
   7.2 You acknowledge and agree that the App may, through user error,
       model error, prompt injection, third-party service error, or
       software bug:
         (a) cause loss, corruption, or unauthorized disclosure of data
             stored on your device;
         (b) damage the configuration, software, or operating state of
             your device;
         (c) cause unintended communications or financial transactions
             performed under your identity; or
         (d) otherwise affect the integrity of your device or accounts.
   7.3 TO THE FULLEST EXTENT PERMITTED BY LAW, NEITHER WE NOR SAMOSA AI
       ARE LIABLE FOR ANY SUCH DAMAGE. YOU ASSUME FULL RESPONSIBILITY FOR
       SAFEGUARDING YOUR DATA (BACKUPS, VERSION CONTROL, RECOVERY PLANS)
       BEFORE GRANTING THE AGENT BROAD DEVICE ACCESS.

8. THIRD-PARTY BACKENDS AND PROVIDERS

   8.1 The App interoperates with third-party AI providers. When you use
       Samosa AI, requests are routed through the Samosa AIR proxy. When
       you use a User-Provided Provider, requests go directly to that
       endpoint under your credentials.
   8.2 Third-party providers have their own terms, privacy practices,
       pricing, and data-handling policies. We do not control and are not
       responsible for the practices of any third-party provider you
       choose to use, including how they retain, train on, log, or share
       the requests sent to them.
   8.3 If you supply your own credentials to a User-Provided Provider,
       those credentials (and the prompts you send) are transmitted to that
       provider under its own terms. We do not warrant the security of any
       user-supplied credentials once transmitted to a third-party.

9. FEES

   9.1 The App itself is provided free of charge unless explicitly stated
       otherwise.
   9.2 You are responsible for all fees charged by your chosen backend:
       Samosa AI's pricing, the pricing of any User-Provided Provider, and
       any data, messaging, or carrier charges incurred by actions the
       agent takes on your behalf.

10. INTELLECTUAL PROPERTY

    10.1 We (and our licensors) retain all right, title, and interest in
         and to the App, including all related software, branding, and
         documentation. No rights are transferred to you other than the
         limited license in Section 3.
    10.2 You retain ownership of content you create or input into the App,
         subject to the licenses required for us and our backends to
         process it as described in our Data Retention Policy.

11. TERMINATION

    11.1 You may stop using the App at any time by uninstalling it.
    11.2 We may suspend or terminate your access to any backend feature at
         any time, with or without notice, if we reasonably believe you
         have violated these Terms or if the underlying service is
         discontinued.
    11.3 Upon termination, the license in Section 3 ends. Sections that by
         their nature should survive (including 5, 6, 7, 8, 12, 13, 14)
         survive termination.

12. DISCLAIMER OF WARRANTIES

    12.1 TO THE FULLEST EXTENT PERMITTED BY LAW, THE APP AND ALL RELATED
         SERVICES ARE PROVIDED "AS IS" AND "AS AVAILABLE," WITHOUT
         WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING WITHOUT
         LIMITATION THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR
         A PARTICULAR PURPOSE, NON-INFRINGEMENT, AND ACCURACY.
    12.2 WE DO NOT WARRANT THAT THE APP WILL BE UNINTERRUPTED, ERROR-FREE,
         SECURE, OR FREE OF HARMFUL COMPONENTS, OR THAT DEFECTS WILL BE
         CORRECTED.

13. LIMITATION OF LIABILITY

    13.1 TO THE FULLEST EXTENT PERMITTED BY LAW, IN NO EVENT WILL WE,
         SAMOSA AI, OR OUR RESPECTIVE AFFILIATES, OFFICERS, DIRECTORS,
         EMPLOYEES, AGENTS, OR LICENSORS BE LIABLE FOR ANY INDIRECT,
         INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, OR FOR
         ANY LOSS OF PROFITS, REVENUE, DATA, GOODWILL, OR USE, ARISING OUT
         OF OR RELATING TO YOUR USE OF (OR INABILITY TO USE) THE APP,
         WHETHER BASED ON WARRANTY, CONTRACT, TORT (INCLUDING NEGLIGENCE),
         STATUTE, OR ANY OTHER LEGAL THEORY, AND WHETHER OR NOT WE HAVE
         BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
    13.2 IN ANY EVENT, OUR TOTAL AGGREGATE LIABILITY FOR ALL CLAIMS
         RELATING TO THE APP WILL NOT EXCEED ONE HUNDRED U.S. DOLLARS
         (USD $100) OR THE EQUIVALENT IN YOUR LOCAL CURRENCY.

14. INDEMNIFICATION

    14.1 You agree to indemnify, defend, and hold harmless Gotcha,
         Samosa AI, and our respective affiliates, officers, directors,
         employees, and agents from and against any third-party claim,
         demand, loss, liability, damage, or expense (including reasonable
         legal fees) arising out of or relating to your use of the App,
         your violation of these Terms, or your violation of any
         applicable law or third-party right.

15. CHANGES TO THESE TERMS

    15.1 We may update these Terms from time to time. The "Last updated"
         date at the top will reflect the current version.
    15.2 Material changes will be communicated through the App (in-app
         notice or first-launch dialog). Continued use of the App after
         such notice constitutes acceptance of the updated Terms.
    15.3 If you do not agree to an update, stop using the App and
         uninstall it.

16. GOVERNING LAW AND DISPUTES

    16.1 These Terms are governed by the laws of India, without regard to
         its conflict-of-laws principles.
    16.2 Any dispute arising out of or relating to these Terms or the App
         will be resolved exclusively in the competent courts of India,
         except where mandatory consumer-protection laws of your country
         of residence give you the right to bring an action in that
         jurisdiction.
    16.3 Nothing in this section limits any non-waivable right you have
         under the consumer laws of your jurisdiction.

17. CONTACT

    17.1 Questions about these Terms can be sent to contact@samosa-ai.com.

— END OF TERMS —
