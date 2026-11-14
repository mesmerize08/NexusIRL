---
layout: default
home: true
title: NexusIRL, a free IRL streaming app for Android
description: >-
  NexusIRL is a free, open-source IRL live streaming app for Android with SRTLA bonding of mobile
  data and Wi-Fi, adaptive bitrate, SRT and RTMP, Twitch and Kick chat and OBS remote control. An
  Android port of Moblin.
---
{%- assign release = site.github.releases | first -%}
{%- assign tag = release.tag_name | default: site.fallback_release -%}
{%- capture apk -%}{{ site.repository_url }}/releases/download/{{ tag }}/NexusIRL-{{ tag }}-arm64-v8a.apk{%- endcapture -%}

<section class="hero">
  <div class="wrap">
    <div>
      <h1>Stream live from anywhere your phone has signal</h1>
      <p class="lede">NexusIRL is a free IRL streaming app for Android. It combines mobile data, Wi-Fi
      and a second phone's connection into one stream, so you stay live when one network fades.</p>
      <div class="actions">
        <a class="button" href="{{ apk }}">Download {{ tag }} for Android</a>
        <a class="button secondary" href="{{ site.repository_url }}">View on GitHub</a>
      </div>
      <p class="fine">Free and open source under the MIT licence. Android 10 or newer. No account, ads
      or tracking. Most phones need this download;
      <a href="{{ site.repository_url }}/releases/tag/{{ tag }}">other versions and checksums</a>.</p>
    </div>

    <svg class="bonding" viewBox="0 0 660 380" role="img" aria-labelledby="bonding-title bonding-desc">
      <title id="bonding-title">How bonding works</title>
      <desc id="bonding-desc">Mobile data, Wi-Fi and a second phone's connection run into NexusIRL,
      which sends them on as one stream to Twitch, Kick, YouTube or your own server.</desc>
      <defs>
        <linearGradient id="stream-gradient" gradientUnits="userSpaceOnUse" x1="440" x2="628" y1="0" y2="0">
          <stop offset="0" style="stop-color: var(--blue)"/>
          <stop offset="1" style="stop-color: var(--magenta)"/>
        </linearGradient>
      </defs>
      <text x="0" y="62">Mobile data</text>
      <text class="sub" x="0" y="84">SIM in this phone</text>
      <text x="0" y="187">Wi-Fi</text>
      <text class="sub" x="0" y="209">Hotspot or router</text>
      <text x="0" y="312">Second phone</text>
      <text class="sub" x="0" y="334">Moblink relay</text>
      <path class="path" d="M178 70 C 270 70, 270 190, 360 190"/>
      <path class="path" d="M178 195 L 360 190"/>
      <path class="path" d="M178 320 C 270 320, 270 190, 360 190"/>
      <path class="flow cell" d="M178 70 C 270 70, 270 190, 360 190"/>
      <path class="flow wifi" d="M178 195 L 360 190"/>
      <path class="flow relay" d="M178 320 C 270 320, 270 190, 360 190"/>
      <path class="out" d="M440 190 L 628 190"/>
      <path class="out-flow" d="M440 190 L 628 190"/>
      <rect class="hub" x="360" y="150" width="80" height="80" rx="18"/>
      <image href="{{ '/assets/img/icon-192.png' | relative_url }}" x="372" y="162" width="56" height="56"/>
      <text x="462" y="164">One stream</text>
      <text class="sub" x="462" y="228">Twitch, Kick, YouTube</text>
      <text class="sub" x="462" y="248">or your own server</text>
    </svg>
  </div>
</section>

<section class="showcase">
  <div class="wrap">
    <figure class="shot">
      <img src="{{ '/assets/img/app-main.webp' | relative_url }}" width="1600" height="720"
           alt="NexusIRL's main screen in landscape: camera preview on the left; stream name, settings, Go live button and quick buttons on the right.">
      <figcaption>The main screen: camera preview, the stream's settings, Go live and your quick buttons.</figcaption>
    </figure>
  </div>
</section>

<section class="section" id="features">
  <div class="wrap">
    <h2>Built for streaming on the move</h2>
    <p class="section-intro">An Android port of <a href="https://github.com/eerimoq/moblin">Moblin</a>,
    the IRL streaming app for iPhone, using the same protocols and settings files.</p>

    <div class="group">
      <div class="group-text">
        <h3>Stay live</h3>
        <p>Walking, cycling or on a train, the stream follows whatever connections the phone has.</p>
      </div>
      <dl class="features">
        <dt>Bonding</dt>
        <dd>Mobile data, Wi-Fi, Ethernet and other phones' data through Moblink relays, sent together over SRTLA. Set which connection to favour.</dd>
        <dt>Adaptive bitrate</dt>
        <dd>The bitrate follows the connection, with Moblin's IRL algorithms or BELABOX's.</dd>
        <dt>SRT, RTMP and RTMPS</dt>
        <dd>H.264 or H.265 video up to 4K, AAC or Opus audio, encrypted SRT, and one stream to several RTMP servers at once.</dd>
        <dt>Recording</dt>
        <dd>MP4 recording on the phone, while streaming or on its own, and snapshots.</dd>
      </dl>
    </div>

    <div class="group flip">
      <div class="group-text">
        <h3>Run the show</h3>
        <p>Scenes, overlays and chat on the phone, and control from wherever you are.</p>
        <dl class="features">
          <dt>Scenes and widgets</dt>
          <dd>Text with timers, location and speed, images, maps, chat and browser sources, LUTs and effects.</dd>
          <dt>Chat and alerts</dt>
          <dd>Twitch, Kick and YouTube chat with BTTV, FFZ and 7TV emotes, and follows, subs, raids and more on screen.</dd>
          <dt>Chat bot</dt>
          <dd>Moblin's <code>!moblin</code> commands let moderators switch scenes, zoom, mute or change the title from chat.</dd>
          <dt>OBS and remote control</dt>
          <dd>Switch OBS scenes from the phone, and control the phone from a browser, another phone or iOS Moblin.</dd>
        </dl>
      </div>
      <figure class="shot">
        <img src="{{ '/assets/img/app-scenes.webp' | relative_url }}" width="1600" height="720" loading="lazy"
             alt="The Scenes screen, listing the Back and Front camera scenes with the scene switch transition setting.">
        <figcaption>Scenes, with buttons for LUTs and widgets.</figcaption>
      </figure>
    </div>

    <div class="group">
      <div class="group-text">
        <h3>Keep it yours</h3>
        <p>Your stream keys and accounts stay on your phone.</p>
        <dl class="features">
          <dt>Protected stream keys</dt>
          <dd>Encrypted with a hardware-backed key, hidden on screen, and kept out of screenshots, logs and exports.</dd>
          <dt>No accounts or tracking</dt>
          <dd>The app talks only to the servers and platforms you choose, and works without Google Play Services.</dd>
          <dt>Works with Moblin</dt>
          <dd>Import Moblin settings from an iPhone, and use Moblink and remote control between the two apps.</dd>
          <dt>Open source</dt>
          <dd>MIT licensed, with reproducible release builds you can check against the source.</dd>
        </dl>
      </div>
      <figure class="shot">
        <img src="{{ '/assets/img/app-stream.webp' | relative_url }}" width="1600" height="720" loading="lazy"
             alt="The Edit stream screen with the stream URL hidden behind dots and a Show button.">
        <figcaption>The stream URL and key stay hidden until you ask to see them.</figcaption>
      </figure>
    </div>
  </div>
</section>

<section class="section" id="start">
  <div class="wrap">
    <h2>Get started</h2>
    <p class="section-intro">NexusIRL is in early test releases. It is tested on real phones and live
    on Twitch and Kick; long sessions and more phones are still being tested.</p>
    <ol class="steps">
      <li>
        <h3>Install the app</h3>
        <p>Download the APK above and open it on your phone. Updates install over the old version and keep your settings.</p>
      </li>
      <li>
        <h3>Add your stream</h3>
        <p>Paste the stream URL and key from Twitch, Kick, YouTube or your SRTLA server, or import your Moblin settings.</p>
      </li>
      <li>
        <h3>Go live</h3>
        <p>Tap Go live. Add Wi-Fi or a second phone for bonding when you need a stronger connection.</p>
      </li>
    </ol>
  </div>
</section>

<section class="section" id="how-it-is-made">
  <div class="wrap split">
    <h2>Made openly, with AI</h2>
    <div>
      <p>NexusIRL is developed with generative AI. Most of its code, tests and documentation are
      written by an AI coding assistant (Anthropic's Claude), directed and reviewed by the
      maintainer. So the project relies on checks anyone can verify:</p>
      <ul class="checks">
        <li>Moblin's own tests, ported, run on every change, with unit and UI tests, CodeQL and a secret scan.</li>
        <li>Streaming is tested against real servers, against iOS Moblin, and on phones on mobile networks.</li>
        <li>Release builds are reproducible, so anyone can confirm an APK was built from the source.</li>
        <li>A <a href="{{ '/audit/' | relative_url }}">release audit</a> by a different AI system (ChatGPT), with its findings fixed.</li>
      </ul>
      <p><a href="{{ site.repository_url }}#how-it-is-made">How NexusIRL is made</a></p>
    </div>
  </div>
</section>

<section class="section" id="questions">
  <div class="wrap">
    <h2>Questions</h2>
    <div class="faq">
      <details>
        <summary>Is there a Moblin app for Android?</summary>
        <p>Moblin itself is iOS only. NexusIRL is an independent, open-source Android port of it, made
        with the same protocols, so an iPhone running Moblin and a phone running NexusIRL can share
        settings, remote control each other and bond each other's mobile data.</p>
      </details>
      <details>
        <summary>Is it free?</summary>
        <p>Yes. It is free and open source under the MIT licence, with no paid tier, accounts or ads.</p>
      </details>
      <details>
        <summary>Which phones does it run on?</summary>
        <p>Android 10 or newer. It is tested on a Samsung Galaxy S26 Ultra and a Huawei P30 Pro.
        <a href="{{ '/testing/community-testing.html' | relative_url }}">Reports from other phones</a>
        are very welcome.</p>
      </details>
      <details>
        <summary>What do I need for bonding?</summary>
        <p>A server that receives SRTLA, such as a self-hosted
        <a href="{{ site.repository_url }}/tree/main/server">SRTLA receiver</a> or an IRL hosting
        service, plus the phone's mobile data and Wi-Fi or a second phone as a Moblink relay.</p>
      </details>
      <details>
        <summary>How do I update?</summary>
        <p>Install the new APK over the old one; your settings are kept.
        <a href="https://github.com/ImranR98/Obtainium">Obtainium</a> can watch for new releases for you.</p>
      </details>
      <details>
        <summary>Will it be on F-Droid?</summary>
        <p>Yes, that is planned for early 2027, when the project meets F-Droid's three-month rule.
        F-Droid's builds already match these APKs byte for byte, so you will be able to switch
        between the two without reinstalling.</p>
      </details>
      <details>
        <summary>Is NexusIRL made with AI?</summary>
        <p>Yes. Most of its code, tests and documentation are written by an AI coding assistant
        (Anthropic's Claude), directed and reviewed by the maintainer, and checked as described
        <a href="#how-it-is-made">above</a>.</p>
      </details>
    </div>
  </div>
</section>

<section class="section" id="guides">
  <div class="wrap split">
    <h2>Guides</h2>
    <div>
      <ul class="checks">
        <li><a href="{{ '/remote-control.html' | relative_url }}">Remote control</a>: from a browser, another phone, over Tailscale or through your own relay.</li>
        <li><a href="{{ '/chat-bot.html' | relative_url }}">Chat bot</a>: control the stream with <code>!moblin</code> commands in chat.</li>
        <li><a href="{{ '/testing/field-test.html' | relative_url }}">Field testing</a>: testing mobile data and bonding away from home.</li>
        <li><a href="{{ '/testing/community-testing.html' | relative_url }}">Testing on your phone</a>: a short checklist for other phones.</li>
        <li><a href="{{ '/privacy-policy.html' | relative_url }}">Privacy policy</a> and <a href="{{ '/threat-model.html' | relative_url }}">threat model</a>.</li>
      </ul>
      <p>For developers: the <a href="{{ '/PORT_PLAN.html' | relative_url }}">port plan</a>,
      the <a href="{{ '/PORTING.html' | relative_url }}">porting tracker</a>,
      <a href="{{ '/RELEASING.html' | relative_url }}">releasing</a> and
      <a href="{{ site.repository_url }}/blob/main/CONTRIBUTING.md">contributing</a>.</p>
    </div>
  </div>
</section>

<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "NexusIRL",
  "description": "Free, open-source IRL live streaming app for Android with SRTLA bonding, adaptive bitrate, SRT and RTMP, Twitch and Kick chat and OBS remote control. An Android port of Moblin.",
  "applicationCategory": "MultimediaApplication",
  "operatingSystem": "Android 10 or newer",
  "url": "https://mesmerize08.github.io/NexusIRL/",
  "downloadUrl": "{{ site.repository_url }}/releases/tag/{{ tag }}",
  "softwareVersion": "{{ tag | remove_first: 'v' }}",
  "image": "https://mesmerize08.github.io/NexusIRL/assets/img/icon-192.png",
  "screenshot": "https://mesmerize08.github.io/NexusIRL/assets/img/app-main.webp",
  "license": "https://opensource.org/licenses/MIT",
  "isAccessibleForFree": true,
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" }
}
</script>
