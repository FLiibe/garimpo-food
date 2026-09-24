# Garimpo Food - Mobile V1

PWA mobile-first per testare il concetto di radar delle offerte estreme nel delivery. La V1 usa solo pagine pubbliche 99Food di São Paulo.

## Cosa fa

- scopre un numero limitato di ristoranti dalla pagina pubblica 99Food di São Paulo;
- legge nomi e prezzi presenti nei menu pubblici;
- esclude accessori economici comuni (molho, shoyu, talher, adicional ecc.);
- assegna un Garimpo Score;
- salva osservazioni di prezzo in Netlify Blobs quando l'ambiente lo consente;
- dopo più scansioni confronta il prezzo corrente con la mediana storica;
- mostra una PWA installabile da browser mobile;
- apre il link 99Food quando l'utente seleziona un'offerta.

## Avvio locale

Richiede Node.js 20+.

```bash
npm install
npx netlify dev
```

Poi apri l'URL mostrato da Netlify Dev.

## Pubblicazione su Netlify

Il repository è già configurato con `netlify.toml`. Collega il repository a Netlify e pubblica. La directory pubblica è `public` e le function sono in `netlify/functions`.

## Perché la prima scansione può sembrare conservativa

Molte pagine mostrano soltanto il prezzo corrente. Il sistema salva le osservazioni: dopo più scansioni può riconoscere che un prodotto normalmente visto a R$ 39,90 è improvvisamente sceso a R$ 0,99 anche quando non viene mostrato un prezzo barrato.

## Limiti V1

- São Paulo soltanto.
- 99Food soltanto.
- massimo 12 ristoranti per singola chiamata, per mantenere il test leggero.
- nessun bypass di autenticazione, captcha o protezioni tecniche.
- il parser è euristico e andrà calibrato dopo il primo test reale.
- prima di aumentare frequenza o copertura va completata la verifica robots.txt/termini applicabili.

## Prossimo step dopo il test

1. Validare il parser con dati live su Netlify.
2. Aggiungere paginazione/categorie 99Food.
3. Creare job pianificato per accumulare storico prezzi.
4. Inserire quartiere/CEP.
5. Aggiungere alert.
6. Integrare iFood con un approccio separato.

<!-- redeploy trigger after scanner diagnostics fix -->
