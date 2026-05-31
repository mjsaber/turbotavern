"""Plain dataclasses shared across pipeline stages. No logic."""
from __future__ import annotations
from dataclasses import dataclass, field
import enum


@dataclass(frozen=True)
class FeedKey:
    source: str
    entity_type: str          # 'hero' | 'trinket'
    mmr_bracket: str          # '100'|'50'|'25'|'10'|'1'
    time_period: str          # 'last-patch'|'past-three'|'past-seven'
    mode: str = "solo"
    region: str = "global"


@dataclass(frozen=True)
class FetchTask:
    """One HTTP fetch. url_mmr is set for heroes (mmr is in the URL); None for
    trinkets (mmr comes from each entry's averagePlacementAtMmr)."""
    source: str
    entity_type: str
    raw_url: str
    time_period: str
    url_mmr: str | None


@dataclass
class NormalizedRow:
    card_id: str
    avg_placement: float
    data_points: int
    pick_rate: float | None = None
    placement_distribution: list | None = None
    extra_json: dict = field(default_factory=dict)


@dataclass
class NormalizedFeed:
    rows: list[NormalizedRow]
    patch: str | None
    schema_fingerprint: list[str]


@dataclass
class FetchResult:
    raw_url: str
    status: int               # 200 | 304
    body: bytes | None
    etag: str | None
    last_modified: str | None


class Outcome(enum.Enum):
    INSERTED = "inserted"
    UNCHANGED = "unchanged"
    NOT_MODIFIED = "not_modified"   # HTTP 304 for the whole URL
