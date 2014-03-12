SteamTrade-Java
===============

An unofficial Java library for Valve's Steam Community item trading service.


About
-----

A heavily modified fork of [Top-Cat's ScrapBank.tf project](https://github.com/Top-Cat/ScrapBank.tf/), designed to be less dependent on the project's bots and SteamKit-specific classes.  
It does not rely on any of Valve's public-facing API for inventory loading, so there is no need for an API key.

The library also supports:
  * Posting to and reading messages from trade chat
  * Private backpacks ("foreign inventories" -- loaded when an item from the inventory is added)
  * Dynamic loading of inventories (just about any game, pretty much)
  * Knowing exactly what inventories you have (scrapes them from the page though, ewww.)
  * GZIPped responses when retrieving pages

Support for stackable items and currencies should be available fairly soon, as well.  Also thinking of how to add concurrent loading of inventories.


Prerequisites, Dependencies and How-To
--------------------------------------

To use the library, one must have a valid Steam sessionId and Steam login token, and also know when a trade is initiated. The library tries to be as independent as possible (e.g., using long values instead of SteamKit-Java's SteamIDs), but ultimately, using SteamKit-Java or a similar Steam client library would be the current best option.

(Though the project is forked off of Top-Cat's mentioned above, it is not my intention to use the similarities between the name of my `SteamTrade-Java` project and his other `SteamKit-Java` project to imply affiliation.)

A small snippet of the library in example use is available as the SampleTrade project.

This is a Maven project and is dependent only on a copy of the `org.json` reference JSON library. The library is bundled with the project as the Java package `bundled.steamtrade.org.json`, to avoid conflicts with existing installs of `org.json`.  
The library has been given a few minor changes to support Java 1.5+ features, mainly using `valueOf()` methods over `new [...]()` to take advantage of the cached values when possible.

Just a Note
-----------

This library, while fairly featured and fleshed out for most uses (read: trading of simple, non-stackable, non-currency Steam items), is still undergoing changes in structure, shedding off old stuff and rearranging and streamlining others; be sure to keep an eye on the methods and what various changes there may be.

Also, the code will be released under the MIT License once the code has been cleaned enough to ensure that copyright is not an issue.
