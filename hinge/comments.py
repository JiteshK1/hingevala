"""Picks a random comment from comments.txt without repeating recent ones."""
import random
from collections import deque
from pathlib import Path


class CommentPicker:
    def __init__(self, path: Path, no_repeat: int = 10):
        self.lines = [
            line.strip()
            for line in Path(path).read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        if not self.lines:
            raise ValueError(f"{path} has no comments in it")
        self.recent = deque(maxlen=max(0, min(no_repeat, len(self.lines) - 1)))

    def pick(self) -> str:
        choices = [c for c in self.lines if c not in self.recent] or self.lines
        comment = random.choice(choices)
        self.recent.append(comment)
        return comment
