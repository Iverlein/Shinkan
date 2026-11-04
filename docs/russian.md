# The Russian version

Shinkan's Russian is written in this repository, by the rules below, not through
a translation platform. `python3 tools/check_strings.py` checks what a machine
can; a new string in `res/values/strings.xml` gets its Russian in the same commit.

## Terms

One English term, one Russian term, in the app, the listing and the README alike.
Where Android has its own Russian word, that word is used, so that a parent finds
in Shinkan what Settings calls the same thing.

| English | Russian | Not |
| --- | --- | --- |
| child lock, in the listing and README | родительский контроль | детский замок, a car door's |
| the hold | защита | |
| Shinkan holds the device | Shinkan управляет устройством | держит |
| release the device | снять защиту с устройства | освободить |
| device owner | владелец устройства | |
| the parent door | раздел «Родителям» | |
| door | дверь | |
| the closed device, the open device | закрытое устройство, открытое устройство | |
| form (closed or open) | режим | |
| launcher, home | главный экран | лаунчер, домашний экран |
| PIN | PIN-код | PIN alone |
| day plan | распорядок дня | |
| stage, span, budget, rest | этап, промежуток, лимит, отдых | |
| window (of an app) | окно | |
| ceiling (of volume) | предел | |
| suspended | приостановлено | |
| creator | автор | |
| player, segment | плеер, фрагмент | |
| private DNS | персональный DNS-сервер | частный DNS |
| USB debugging | отладка по USB | |
| hash | хеш | хэш |

## Address

The parent is *вы*, in lower case. The child's pages avoid addressing anyone:
«Здесь появятся просмотренные видео», not «видео, которые вы смотрите». A *вы*
to a child reads as cold, a *ты* would be wrong for the parent who reads the same
page.

## Grammar

- **Buttons** are infinitives: *Сохранить*, *Применить*. *Отмена* and *Готово* stay
  as Android has them.
- **Counts** are `plurals` with all of `one`, `few`, `many` and `other`, or a form
  that needs no agreement: *Сайтов: 5*, *Ещё 3 мин*, never *Осталось 1 мин*. `one`
  in Russian also covers 21 and 31, and 11 is `many`.
- **Words beside a number shown apart** are plurals too: the rest page's
  *минута отдыха* stands under its number, so its `one` has no `%d`.
- **An app's or a creator's name** takes no agreement: *приложение %1$s*,
  *автора «%1$s»*.
- **A weekday** comes from the locale in the nominative: *Откроется: вт, 06:00*, as
  *в вт* cannot be said.

## Typography

- Quotes are « »; a dash is — with a no-break space before it; the ellipsis is one
  character, ….
- A no-break space, written ` `, between a number and its unit (*5 мин*,
  *60 %*, *10 с*) and after a one-letter or short preposition or conjunction.
- *ё* is written.
- Units have no dot: *мин*, *с*. Hours are 24-hour.
- *ОК* is Cyrillic, as in Android.

## What stays English

The words of the policy text and the block list text (SPEC §4.4, §6.4): `mode`,
`plan`, `site`, and the rest are a format. A parser's complaint about them is
Russian with those words kept: «после mode нужно closed или open». Brands stay
spelled as their owners spell them.

## Checks by eye

A long word breaks a narrow row where no check sees it, and only a screen shows it.
After a change to a row of buttons, chips or labels, look at the page in Russian on
the emulator at phone width: `adb root`, then
`adb shell 'setprop persist.sys.locale ru-RU; setprop ctl.restart zygote'`.
