# Разные голоса для нагрузочного теста

Десять настоящих английских речевых клипов длительностью 4–23 секунды, от девяти дикторов. Пять коротких, три средних и два длинных клипа чередуются в `load/cmp-real-clips.js` через `AUDIO_MANIFEST=./fixtures/librispeech-test-clean/manifest.json`. Каждый файл перекодирован из FLAC в моно OGG/Opus 16 кГц, 32 кбит/с; содержимое речи не изменялось.

Источник: [LibriSpeech `test-clean`](https://www.openslr.org/12/), авторы Vassil Panayotov, Guoguo Chen, Daniel Povey и Sanjeev Khudanpur. Лицензия [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Исходный идентификатор каждого фрагмента сохранён в имени файла. Это речь из аудиокниг, поэтому результат не заменяет проверку спонтанных голосовых сообщений пользователей.
