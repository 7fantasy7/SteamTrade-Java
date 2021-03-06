SteamTrade-Java
===============

An unofficial Java library for Valve's Steam Community item trading service.


About
-----

A heavily modified fork of [Top-Cat's ScrapBank.tf project](https://github.com/Top-Cat/ScrapBank.tf/), designed to be less dependent on the project's bots and SteamKit-specific classes.  
It does not rely on any of Valve's public-facing API for inventory loading; there is no need for an API key.

The library, if you're unfamiliar with Steam trading, also supports:
  * Posting to and reading messages from trade chat
  * Purely private backpacks ("foreign inventories" -- loaded when an item from the inventory is added)
  * Dynamic loading of inventories (just about any game, pretty much)
  * Knowing exactly what inventories you have (scrapes them from the page though, ewww.)
  * GZIPped responses when retrieving pages
  * Loading of large inventories as needed (Valve made it so you load 2500? items at a time, this makes it so it only loads up to whatever item the other user has put up)
  
Additionally, the library has extendable support via:
  * Pluggable support for game-specific items: Now you can extend the item support to, say, add WebAPI schema / inventory connectivity and handle any other items yourself (mostly; you get access to the inventory data in the scope of the asset to be loaded).

Potential additions in the future include support for:
  * ~~Stackable items and currencies~~ Getting there.  Currencies are viewable now, though they will only show up by name.  No amount; similar case with stackables.
  * Inventory caching?  For card swap bots and possibly other traders, the assetid could be loaded from a previous inventory download.

The three included projects are:
  * SteamTrade-Java:  The core of the project, handling trade connectivity and all.
  * SteamTrade-Example:  A non-functioning example to show what can be done with the library.
  * AssetBuilder-TF2:  An example of pluggable support for Team Fortress 2 items, exposing its item definition index and quality from the available item data, plus comments on how to extend support via the official WebAPI.


Prerequisites, Dependencies and How-To
--------------------------------------

To use the library, one must have a valid Steam sessionId and Steam login token, and also know when a trade is initiated. The library tries to be as independent as possible (e.g., using long values instead of SteamKit-Java's SteamIDs), but ultimately, using SteamKit-Java or a similar Steam client library would be the current best option.

(Though the project is forked off of Top-Cat's mentioned above, it is not my intention to use the similarities between the name of my `SteamTrade-Java` project and his other `SteamKit-Java` project to imply affiliation.)

A small snippet of the library in example use is available as the SampleTrade project.

This is a Maven project and is dependent only on a copy of the `org.json` reference JSON library. The library is bundled with the project as the Java package `bundled.steamtrade.org.json`, to avoid conflicts with existing installs of `org.json`.  
The library has been given a few minor changes to support Java 1.5+ features, mainly using `valueOf()` methods over `new [...]()` to take advantage of the cached values when possible.


Just a Note
-----------

This library, while fairly featured and fleshed out for most uses (read: trading of simple, non-stackable, non-currency Steam items), is still undergoing changes in structure, shedding off old stuff and rearranging and streamlining others; be sure to keep an eye on the methods and what various changes there may be.  The example trade listener will be updated to reflect changes as they come.

Probably not going to version this and just keep it as a running snapshot.

Also, the code will be released under the MIT License once the code has been cleaned enough to ensure that copyright is not an issue.
