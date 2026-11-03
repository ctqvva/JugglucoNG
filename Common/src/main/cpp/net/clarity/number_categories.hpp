// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cmath>
#include <span>

namespace clarity {
// Keep Libre's values: 0 unassigned, 1 rapid, 2 long, 3 carbs, 4 comment,
// 5 do not send. Only UI value 6 is Clarity-specific; it is stored in bloodvar.
inline int numberCategory(int libreKind, bool blood) {
    if (blood)
        return 6;
    return libreKind >= 1 && libreKind <= 5 ? libreKind : 0;
}
inline int libreCategoryForClarity(int selected, int previous) {
    if (selected != 6)
        return selected;
    // Keep an existing Libre mapping for the fingerstick label. For a new
    // unmapped label, Libre can carry the label and quantity as a comment.
    return previous >= 1 && previous <= 5 ? previous : 4;
}
inline const char *validateNumberCategories(std::span<const int> kinds,
                                             std::span<const float> weights) {
    if (kinds.empty() || kinds.size() != weights.size())
        return "No labels, or labels changed; reopen Categories";
    bool blood = false;
    for (size_t i = 0; i < kinds.size(); ++i) {
        if (kinds[i] < 1 || kinds[i] > 6)
            return "Categorize every label before enabling Send amounts";
        if (kinds[i] == 3 && (!std::isfinite(weights[i]) || weights[i] <= 0))
            return "Carbohydrate weight must be a positive number";
        if (kinds[i] == 6) {
            if (blood)
                return "Choose only one glucose measurement label (the calibration label)";
            blood = true;
        }
    }
    return nullptr;
}
} // namespace clarity
