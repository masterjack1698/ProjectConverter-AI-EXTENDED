# 📄 Scheda di Contesto — Modulo AAF per ProjectConverter

_Aggiornata al 2026-10-03 — consegnare questa scheda a una nuova sessione AI per continuare lo sviluppo senza ricaricare il contesto precedente._

## 🎯 Stato del progetto

ProjectConverter (`F:\AI Projects\ProjectConverter`, Java 25 + Maven + JavaFX, architettura Bitwig DawProject API di Jürgen Moßgraber) converte progetti DAW. Abbiamo aggiunto il supporto di **lettura AAF** (.aaf) con conversione verso **Reaper (.rpp)** e **DawProject (.dawproject)**.

**Stato: FUNZIONANTE E VERIFICATO** (con file reale `TIM_DEF.20260925190647.aaf`, Cubase export):
- ✅ AAF con audio embedded → RPP e dawproject
- ✅ AAF con audio esterno (file WAV separati) → RPP e dawproject
- ✅ Posizionamento timeline, trim/offset sorgente, canali L/R, nomi tracce
- ✅ dawproject validato (`DawProject.validate` OK) e importato correttamente in Cubase (clip normali, non "nested vuote") e in Reaper (87 item con POSITION/LENGTH/SOFFS corretti)

## 📁 File del modulo

- `src/main/java/de/mossgrabers/projectconverter/format/aaf/AAFParser.java` — parser AAF-SSF completo
- `src/main/java/de/mossgrabers/projectconverter/format/aaf/AafDetector.java` — bridge UI → dawproject (`ISourceFormat`)
- `src/main/java/de/mossgrabers/projectconverter/format/aaf/AafMediaFiles.java` — `IMediaFiles` per i media AAF
- Modificati: `format/reaper/ReaperCreator.java` (NO — revert fatto, non toccare), `Strings.properties` (chiavi `IDS_NOTIFY_AAF_*`), `run.cmd` (JDK 25 + `--module-path target\lib`)

## 🔑 Come funziona il parser AAF-SSF (formato reale, decodificato da byte)

1. **Contenitore**: OLE2 Compound File (Apache POI `poi` 5.2.5 — NON usare `poi-ssff` che non esiste).
2. **Property streams**: ogni oggetto AAF ha uno stream `properties` con formato: magic `4c 20` + count (2 LE) + entry da 6 byte (pid 2 LE, stored-form 2 LE, length 2 LE) + valori concatenati.
3. **Stored forms**: `0x22` = strong ref (valore = nome UTF-16 della storage figlia), `0x32` = strong vector (nomi multipli), `0x82` = dato (int/rationale/AUID/stringa), `0x02` weak ref, `0x42` data stream.
4. **Strong reference vectors**: i figli sono directory `<NomeProprietà>-<pid>{<key>}`; l'ordine REALE (timeline) è dato dallo stream `<nome> index` (12 byte header + 4 byte key LE per elemento; set: 12+3+40). NON ordinare per nome/chiave!
5. **MetaDictionary** (`MetaDictionary-1/ClassDefinitions-3{..}/Properties-9{..}`): le PropertyDef mappano pid locale → nome proprietà (es. `Length`, `SourceID`, `MobID`, `Slots`, `Components`, `URLString`). ~168 nomi nel file Cubase.
6. **Nomi oggetto**: la classe non è nel nome dir; identificare i ruoli semanticamente (vedi sotto).

## 🌳 Struttura logica AAF (come appare nei file reali)

```
Header-2/Content-3b03/Mobs-1901{xx}          ← ogni mob = directory
  Slots-4403{0..n}                           ← TimelineMobSlot
    SlotID, EditRate (rationale 8B), SlotName, Segment-4803 (strong ref)
  EssenceDescription-4701                    ← SourceMob only (MasterMob: VUOTA, 0 proprietà!)
    Locator-2f01{0}                          ← NetworkLocator: proprietà URLString (UTF-16)
                                                con path esterno ("file:///F:/....wav")
CompositionMob (radice non referenziata da alcun SourceID)
  slot timecode + slot audio ("Audio 1", "Audio 2")
  Segment-4803 = Sequence con Components-1001{...} (ordine = timeline!)
    SourceClip: Length, SourceID (32B MobID), SourceMobSlotID, StartTime (trim)
    OperationGroup: InputSegments-b02{0..n} ← 1 elemento per CANALE (stereo: L/R)
    Filler: avanza la timeline come gli altri
MasterMob (referenziato dalle SourceClip della composition)
  1 slot per canale; ogni Segment = SourceClip → SourceMob (StartTime=0 tipicamente)
SourceMob (ha EssenceDescription con proprietà vere + Locator con il path WAV)
```

## ⏱️ Algoritmo di posizionamento (VERIFICATO su dati reali)

- `posizione = somma accumulata delle Length dei componenti precedenti (Filler inclusi) / EditRate dello slot`
- `EditRate` = rationale nello slot (es. 48000/1)
- **Catena di riferimenti**: SourceClip → MasterMob → SourceMob. Ad OGNI hop il `SourceMobSlotID` del clip CORRENTE seleziona lo `SlotID` dello slot nel mob referenziato (critico per i canali L/R: stesso MasterMob, slot diversi → file A01/A02 diversi). Il trim (`StartTime`) si accumula lungo la catena.
- Skip dei SourceMob con MobID=0 (tracce finte); la composition è il mob non referenziato.
- Trim verificato legittimo: `playStart + duration ≤ durata file` (i WAV hanno pre/post-roll).

## 🐛 Bug storici risolti (non ripresentarsi)

1. `collectSourceReferences`/`firstSourceClip`: il `Segment` è figlio dello **slot**, non del mob (`slot.childrenByName`, non `mob.childrenByName`).
2. `hasEssenceDescription`: i MasterMob hanno EssenceDescription VUOTA → conta solo se `!child.props.isEmpty()`.
3. Riferimenti esterni: path nel figlio `Locator-*` (proprietà `URLString`), NON "EssenceFileReference"; normalizzare URI `file://` e `%20` (`toFilePath()`).
4. MobID dell'audio dal **mob padre**, non dall'EssenceDescription.
5. `isAbsolutePath` deve accettare `C:\...`, non solo `/`.
6. Null-safety: `AudioClip.audioFile` può essere null (esterno mancante) → guard in AafDetector.

## 📤 Formato dawproject in output (fedele a Cubase — VERIFICATO)

```xml
<Clip time dur playStart name enable="true">      ← posizione timeline + sezione
  <Clips>
    <Clip contentTimeUnit="seconds" time="0" duration="durata_file">
      <Audio algorithm="raw" channels sampleRate duration><File path external="false"/></Audio>
    </Clip>
  </Clips>
</Clip>
```

- `playStart` = offset nel media (semantica verificata identica a Cubase); `duration` esterna = sezione; interna = file intero.
- Obbligatorio `project.application.version` (senza: XML invalido, Cubase degrada il progetto).
- `Audio.channels`/`sampleRate` valorizzati dall'header WAV (Bitwig li richiede; Reaper li ignora).
- Canali: OperationGroup stereo → tracce separate "L"/"R" mono (files split mono).
- ReaperCreator appiattisce nativamente i clip annidati (ramo `clip.content instanceof Clips`) → RPP corretto senza modifiche.

## 🧪 Test e harness (nello scratchpad, ricrearli se servono)

- Compilazione: `JAVA_HOME` SENZA spazio finale; `TMP`/`TEMP` devono puntare a una cartella esistente (`F:\AI_Projects\tmp`), altrimenti Maven fallisce con "Error creating file with javac arguments".
- Harness headless: istanziare `AafDetector` con `INotifier` stub → `read(aaf)`; per dawproject usare `DawProjectCreator.toXML` via reflection (o `DawProject.save`); per Reaper chiamare `creator.getEditPane()` e **selezionare il primo toggle di `arrangementOrScenesGroup`** (senza: `convertScenes` su progetto vuoto → RPP senza item, falso positivo!). Serve `Platform.startup/exit`.
- File di test: `F:\Grafica\IG_5\AAF\TIM_DEF.20260925190647.aaf` (+58 WAV esterni A1-*/A2-*); esempio dawproject autentico Cubase nello scratchpad (`esempio cubase.dawproject`).

## 🚀 Possibili sviluppi futuri

- Fade/crossfade (`OperationGroup::Parameters`) — oggi i clip si accavallano alle transizioni
- Marker e region AAF
- Nomi tracce: Cubase non esporta i nomi nell'AAF (usiamo SlotName; possibile fallback al nome file sorgente)
- Timecode slot della composition (oggi ignorato; potrebbe servire per l'offset iniziale progetto)
- Multi-canale >2 (già supportato dallo splitting `channel` in `ClipEntry`)
