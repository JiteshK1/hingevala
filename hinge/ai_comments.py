"""Writes a comment that answers the prompt or photo being liked, using Claude.

Needs ANTHROPIC_API_KEY in the environment of app.py. Any failure returns None, and the
bot falls back to a line from comments.txt, so a missing key or network hiccup never
stops a run."""
import re
from typing import List, Optional

import anthropic

SYSTEM = """You write the comment that goes with a like on Hinge, the dating app.

You get the item being liked (a prompt and its answer, or a photo) and the rest of the \
text on her profile. Write one comment that shows you actually read it: react to \
something specific, add a playful twist or your own take, or ask an easy question she'd \
enjoy answering. Sound like a real person texting: casual, warm, lightly flirty, \
confident, never needy.

Rules:
- One or two short sentences, under 140 characters in total.
- For a prompt, respond to her answer, not the question.
- For a photo you can't see it, so never describe or guess what's in it. Riff on \
something else in her profile instead, or keep it light and general.
- Nothing sexual, no comments on her body or looks, no pickup-line cheese, no \
"hey beautiful".
- Match the language she writes in (Hinglish if she uses Hinglish).
- At most one emoji, and only if it fits.
- Reply with the comment only: no quotes, no explanation."""

# Hinge cuts comments off past this; keep a margin.
MAX_CHARS = 150


class AICommenter:
    def __init__(self, model: str, style: str = ""):
        self.model = model
        self.style = style.strip()
        self.client = anthropic.Anthropic(max_retries=2, timeout=60.0)

    def write(self, target: str, profile_texts: List[str]) -> Optional[str]:
        """A comment for the liked item, or None (the caller then uses comments.txt)."""
        profile = "\n".join(f"- {t}" for t in profile_texts if t != target) or "(nothing else)"
        request = f"Item being liked:\n{target}\n\nRest of her profile:\n{profile}"
        if self.style:
            request += f"\n\nExtra style notes from me: {self.style}"
        response = self.client.beta.messages.create(
            model=self.model,
            max_tokens=4000,
            output_config={"effort": "low"},
            # If a safety check declines the request, the API retries it on a fallback
            # model instead of returning a refusal.
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            system=SYSTEM,
            messages=[{"role": "user", "content": request}],
        )
        if response.stop_reason in ("refusal", "max_tokens"):
            return None
        text = "".join(b.text for b in response.content if b.type == "text")
        return clean(text)


def clean(text: str) -> Optional[str]:
    """One line, no wrapping quotes, short enough for Hinge; None if unusable."""
    text = re.sub(r"\s+", " ", text).strip().strip('"“”').strip()
    if not text or len(text) > MAX_CHARS:
        return None
    return text
