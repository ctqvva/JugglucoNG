// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
class Numdata;
#ifdef JUGGLUCO_CLARITY
void startclaritythread();
void wakeclarity();
void claritynums(Numdata *data);
void claritynumsremove(Numdata *data);
#else
inline void startclaritythread() {}
inline void wakeclarity() {}
inline void claritynums(Numdata *) {}
inline void claritynumsremove(Numdata *) {}
#endif
