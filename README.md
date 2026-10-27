# tibudget-collector-utils
Usefull classes to develop a ti'bu collector

## Enregistrer et rejouer un scénario HTTP

```java
OkHttpClient client = new OkHttpClient.Builder().build();
OkHttpInternetProvider recording = new OkHttpInternetProvider(client)
        .htmlDir("target/debug-html")
        .harDir("src/test/resources/scenario")
        .downloadDir("target/downloads")
        .rateLimiter(2) // Requêtes par seconde ; accepte aussi une instance de RateLimiter.
        .cookiesFile("target/cookies.bin");

InternetProvider replay = new OkHttpInternetProvider(client)
        .replayDir(recording.getHarDir());
```

Le constructeur prend uniquement l'`OkHttpClient`. Sans répertoire HTML ou HAR,
aucun trafic n'est enregistré. Les méthodes fluent acceptent `File` ou `String`
et créent les répertoires de sortie si nécessaire ; `htmlDir((File) null)` et
`harDir((File) null)` désactivent le format correspondant. Les fichiers des deux
formats partagent le même index. Un sous-répertoire `yyyy-MM-dd_HH-mm-ss_SSS`
est créé sous chaque base HTML/HAR. Si ce nom existe déjà, un suffixe `_1`, `_2`,
etc. est ajouté jusqu'à trouver un nom libre. HTML et HAR réutilisent le même
sous-répertoire lorsqu'ils ont la même base.
`getHtmlDir()` et `getHarDir()` renvoient ces répertoires de session effectifs,
à utiliser pour sauvegarder la référence JSON ou configurer le rejeu.
Deux providers utilisent des sessions distinctes, même avec la même base.
Configurez les chemins avant les appels HTTP.

`downloadDir` vaut `target/downloads` par défaut, créé au premier téléchargement.
`cookiesFile` installe un `AdvancedCookieJar` persistant en conservant les autres
options du client fourni. Sans cet appel, le cookie jar du client est conservé.
`replayDir` doit désigner un répertoire existant et désactive les appels réseau.
En rejeu, le rate limiter est ignoré pour GET, POST et les téléchargements.

Le HTML contient le corps lisible de la réponse (le chemin local pour un téléchargement).

Avec `harDir` défini, chaque échange est enregistré au format HAR 1.2 dans un fichier tel que
`0001_get_example.com_page.har`. Le rejeu suit l'ordre des appels et vérifie
la méthode et l'URL exacte (paramètres compris). Un appel différent ou supplémentaire,
un fichier absent ou un HAR invalide provoque une `IOException`.
Les corps POST et les en-têtes de requête sont enregistrés, mais ne sont pas
comparés pendant le rejeu.

Le statut, les en-têtes, le corps et la dernière localisation de la réponse sont
restitués. La localisation utilise le champ d'extension HAR `_location`.
Les téléchargements sont inclus en base64 et recréés localement au rejeu.
Les anciens fichiers `.html` restent lisibles avec un statut 200 et sans en-têtes ;
leurs téléchargements nécessitent que le fichier local d'origine existe encore.

## Comparer le résultat d'une collecte

`CollectorResultJson` sérialise les comptes, transactions et paiements récurrents
actuellement exposés par un `CollectorPlugin`, sans lancer la collecte.

```java
File reference = new File("src/test/resources/scenario/collection.json");

// Après la collecte réelle, lors de la création du scénario :
CollectorResultJson.save(reference, collector);

// Après la collecte hors ligne, dans le test :
CollectorResultJson.assertMatches(reference, offlineCollector);

// Ou pour obtenir simplement le JSON :
String json = CollectorResultJson.toJson(collector);

// Pour conserver les UUID d'origine :
String rawJson = CollectorResultJson.toJson(collector, false);
// Le même booléen est disponible sur save et assertMatches.
```

Le JSON UTF-8 est indenté, ses clés sont triées (y compris dans les métadonnées)
et ses dates sont en ISO-8601 UTC, avec conservation des millisecondes.
Les champs des DTO et les valeurs nulles sont conservés, ainsi que l'ordre
des listes. Par défaut, `toJson` stabilise les UUID des comptes et paiements récurrents
avec `UUID.nameUUIDFromBytes` sur `account:<id>` et `recurringPayment:<id>` en UTF-8.
Les références exactes à leurs anciens UUID sont remplacées partout dans le JSON,
y compris dans les valeurs et clés des métadonnées, sans modifier les DTO d'origine.
Les champs `counterpartyUuid` restent inchangés (quelle que soit leur casse).
Les ID doivent être renseignés ; un ID absent ou un ancien UUID associé à des ID
incompatibles déclenche une `IllegalArgumentException`.
`save` et `assertMatches` utilisent cette même stabilisation par défaut.
 L'assertion ignore l'indentation et l'ordre des clés du fichier de
référence ; une différence de données fait échouer le test avec les résultats
attendu et obtenu. Elle ne crée ni ne remplace le fichier de référence.

Les pièces jointes sont sérialisées par leur contenu en base64 : les chemins
temporaires de téléchargement peuvent donc changer au rejeu, mais le contenu
doit être identique. Un fichier illisible fait échouer la sérialisation.
Les ID, UUID des contreparties et dates de mise à jour doivent rester stables
entre la collecte réelle et le rejeu. Les paramètres,
identifiants de connexion et autres données de configuration du collector ne
font pas partie du résultat sérialisé.
