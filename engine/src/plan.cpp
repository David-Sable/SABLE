// Queue planner. See plan.h and docs/plan-json.md.
#include "sable/plan.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <limits>
#include <map>
#include <optional>
#include <string_view>
#include <utility>
#include <vector>

#include "sable/dmath.h"
#include "sable/intent.h"

namespace sable {
namespace {

// ---------------- Minimal JSON DOM (numbers via from_chars: exact round trip of to_chars output) ----------------
struct J {
  enum class T { Null, Bool, Num, Str, Arr, Obj } t = T::Null;
  bool b = false;
  double n = 0.0;
  std::string s;
  std::vector<J> a;
  // Object members as parallel lists: a pair<string, J> would need J complete here (libstdc++ rejects it).
  std::vector<std::string> keys;
  std::vector<J> vals;

  const J* get(std::string_view k) const {
    if (t != T::Obj) return nullptr;
    for (std::size_t i = 0; i < keys.size(); ++i)
      if (keys[i] == k) return &vals[i];
    return nullptr;
  }
  std::optional<double> num(std::string_view k) const {
    const J* v = get(k);
    return v && v->t == T::Num ? std::optional<double>(v->n) : std::nullopt;
  }
  double num(std::string_view k, double dflt) const { return num(k).value_or(dflt); }
  bool flag(std::string_view k) const {
    const J* v = get(k);
    return v && v->t == T::Bool && v->b;
  }
  std::string str(std::string_view k) const {
    const J* v = get(k);
    return v && v->t == T::Str ? v->s : std::string();
  }
  bool present(std::string_view k) const {
    const J* v = get(k);
    return v && v->t != T::Null;
  }
};

class Parser {
 public:
  explicit Parser(std::string_view s) : s_(s) {}
  bool parse(J& out) {
    if (!value(out)) return false;
    ws();
    return i_ == s_.size();
  }

 private:
  std::string_view s_;
  std::size_t i_ = 0;
  int depth_ = 0;

  void ws() {
    while (i_ < s_.size() && (s_[i_] == ' ' || s_[i_] == '\n' || s_[i_] == '\r' || s_[i_] == '\t')) ++i_;
  }
  bool lit(std::string_view w) {
    if (s_.substr(i_, w.size()) != w) return false;
    i_ += w.size();
    return true;
  }
  bool string(std::string& out) {
    if (i_ >= s_.size() || s_[i_] != '"') return false;
    ++i_;
    while (i_ < s_.size() && s_[i_] != '"') {
      char c = s_[i_++];
      if (c == '\\') {
        if (i_ >= s_.size()) return false;
        const char e = s_[i_++];
        switch (e) {
          case 'n': c = '\n'; break;
          case 't': c = '\t'; break;
          case 'r': c = '\r'; break;
          case 'b': c = '\b'; break;
          case 'f': c = '\f'; break;
          case 'u':
            if (i_ + 4 > s_.size()) return false;
            i_ += 4;
            c = '?';  // keys and ids are ASCII; other code points are not needed by the planner
            break;
          default: c = e;  // \" \\ \/
        }
      }
      out.push_back(c);
    }
    if (i_ >= s_.size()) return false;
    ++i_;
    return true;
  }
  bool value(J& v) {
    if (++depth_ > 64) return false;
    ws();
    if (i_ >= s_.size()) return false;
    bool ok = true;
    const char c = s_[i_];
    if (c == '{') {
      v.t = J::T::Obj;
      ++i_;
      ws();
      if (i_ < s_.size() && s_[i_] == '}') ++i_;
      else
        for (;;) {
          ws();
          std::string k;
          J x;
          if (!string(k)) { ok = false; break; }
          ws();
          if (i_ >= s_.size() || s_[i_++] != ':' || !value(x)) { ok = false; break; }
          v.keys.push_back(std::move(k));
          v.vals.push_back(std::move(x));
          ws();
          if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
          if (i_ < s_.size() && s_[i_] == '}') { ++i_; break; }
          ok = false;
          break;
        }
    } else if (c == '[') {
      v.t = J::T::Arr;
      ++i_;
      ws();
      if (i_ < s_.size() && s_[i_] == ']') ++i_;
      else
        for (;;) {
          J x;
          if (!value(x)) { ok = false; break; }
          v.a.push_back(std::move(x));
          ws();
          if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
          if (i_ < s_.size() && s_[i_] == ']') { ++i_; break; }
          ok = false;
          break;
        }
    } else if (c == '"') {
      v.t = J::T::Str;
      ok = string(v.s);
    } else if (lit("null")) {
      v.t = J::T::Null;
    } else if (lit("true")) {
      v.t = J::T::Bool;
      v.b = true;
    } else if (lit("false")) {
      v.t = J::T::Bool;
    } else {
      v.t = J::T::Num;
      const auto r = std::from_chars(s_.data() + i_, s_.data() + s_.size(), v.n);
      ok = r.ec == std::errc() && r.ptr != s_.data() + i_;
      if (ok) i_ = static_cast<std::size_t>(r.ptr - s_.data());
    }
    --depth_;
    return ok;
  }
};

std::string num(double v) {
  if (!std::isfinite(v)) return "null";
  char b[40];
  const auto r = std::to_chars(b, b + sizeof b, v);
  return std::string(b, r.ptr);
}

// ---------------- Analysis JSON -> the inputs of the gain rules ----------------
constexpr double kProvisionalOffset = 8.0;  // §22.1: assume a -8 LUFS master
constexpr double kJitTpMargin = 1.0;        // §22.2 M_jitTP
constexpr double kPerceptualBound = 3.0;    // §16.2 B_p

struct Track {
  LoudnessResult r;
  TrackUnderstanding u;
  bool decodeClipped = false;
  std::optional<double> pleOffset() const {  // §18: (PLE_anchor - L_anchor), clamped as in §16.2
    if (!u.anchorPle || !u.anchorLufs) return std::nullopt;
    return std::clamp(*u.anchorPle - *u.anchorLufs, -kPerceptualBound, kPerceptualBound);
  }
};

Character characterOf(const std::string& s) {
  for (Character c : {Character::QuietIntimate, Character::BuildRelease, Character::DynamicArc, Character::SustainedDense, Character::Steady})
    if (s == toString(c)) return c;
  return Character::Steady;
}

Track trackOf(const J& a) {
  Track t;
  t.r.integratedLufs = a.num("integratedLufs");
  t.r.truePeakDbtp = a.num("truePeakDbtp");
  t.r.shortFile = a.flag("shortFile");
  t.decodeClipped = a.flag("decodeClipped");
  t.u.anchorLufs = a.num("anchorLufs");
  t.u.anchorPle = a.num("anchorPle");
  t.u.anchorSec = a.num("anchorSec", 0.0);
  t.u.character = characterOf(a.str("character"));
  if (const J* p = a.get("anchorBlockPower"); p && p->t == J::T::Arr)
    for (const J& x : p->a)
      if (x.t == J::T::Num) t.u.anchorBlockPower.push_back(x.n);
  t.u.openLufs = a.num("openLufs");
  t.u.endLufs = a.num("endLufs");
  if (a.flag("fadeIn")) t.u.fadeIn = std::make_pair(0.0, 0.0);
  if (a.flag("fadeOut")) t.u.fadeOut = std::make_pair(0.0, 0.0);
  return t;
}

struct Item {
  enum class Source { Full, Jit, Provisional } source = Source::Provisional;
  std::optional<Track> track;
  bool albumRun = false, manual = false;
  double baseDb = 0.0, capDb = std::numeric_limits<double>::infinity();
  double naturalSeamedDb = 0.0, manualSeamedDb = 0.0, learnedDb = 0.0;
  std::vector<Rule> rules;
};

std::string rulesJson(const std::vector<Rule>& rules) {
  std::string o = "[";
  for (std::size_t i = 0; i < rules.size(); ++i) {
    if (i) o += ',';
    o += "{\"id\":\"" + rules[i].id + "\",\"deltaDb\":" + num(rules[i].deltaDb) + "}";
  }
  return o + "]";
}

}  // namespace

std::string planQueueJson(const std::string& input) {
  J in;
  if (!Parser(input).parse(in) || in.t != J::T::Obj) return "{\"error\":\"bad input\"}";
  GainContext ctx;
  ctx.targetLufs = in.num("targetLufs", -14.0);
  ctx.intimacyLu = in.num("intimacyLu", 1.0);
  ctx.ceilingDbtp = in.num("ceilingDbtp", -1.0);
  ctx.limiter = in.flag("limiter");
  ctx.contourHeadroomDb = std::max(0.0, in.num("contourHeadroomDb", 0.0));  // §24.4, Quiet contour on
  const double nowDays = in.num("nowDays", 0.0);
  const double outputCal = std::clamp(in.num("outputCalDb", 0.0), -3.0, 3.0);  // §20.2
  const std::string albumMode = in.str("albumMode");                            // "auto" | "track" | "album"

  // §17.2 album gains, decided once per album key (only when every track of the album is analysed).
  std::map<std::string, std::optional<GainDecision>> albumGain;
  if (const J* albums = in.get("albums"); albums && albums->t == J::T::Obj)
    for (std::size_t ai = 0; ai < albums->keys.size(); ++ai) {
      const std::string& key = albums->keys[ai];
      const J& alb = albums->vals[ai];
      std::optional<GainDecision> d;
      const J* tracks = alb.get("tracks");
      if (alb.flag("complete") && tracks && tracks->t == J::T::Arr && !tracks->a.empty()) {
        std::vector<Track> ts;
        for (const J& a : tracks->a) ts.push_back(trackOf(a));
        std::vector<const LoudnessResult*> rs;
        std::vector<const TrackUnderstanding*> us;
        bool clipped = false;
        for (const Track& t : ts) {
          rs.push_back(&t.r);
          us.push_back(&t.u);
          clipped = clipped || t.decodeClipped;
        }
        GainContext c = ctx;
        c.decodeClipped = clipped;  // conservative: the album cap uses M_clipTP if any member was clipped
        d = decideAlbumGain(rs, us, c);
        if (std::any_of(d->rules.begin(), d->rules.end(), [](const Rule& r) { return r.id == "ALBUM_NO_MEMBERS"; })) d.reset();
      }
      albumGain[key] = d;
    }

  std::vector<Item> items;
  const J* list = in.get("items");
  if (list && list->t == J::T::Arr)
    for (const J& x : list->a) {
      Item it;
      it.manual = x.flag("manual");
      if (const J* l = x.get("learn"); l && l->t == J::T::Obj) {  // §19.2: decay to now, then combine
        const double t = decayedOffset({l->num("trackDb", 0.0), l->num("trackDays", nowDays)}, nowDays);
        const double a = decayedOffset({l->num("artistDb", 0.0), l->num("artistDays", nowDays)}, nowDays);
        it.learnedDb = appliedOffset(t, a, static_cast<int>(l->num("artistTracks", 0.0)));
      } else {
        it.learnedDb = std::clamp(x.num("learnedDb", 0.0), -2.0, 2.0);  // §19.2 clamp (a value frozen at item start)
      }
      const std::string key = x.str("album");
      const bool inAlbum = !key.empty() && albumMode != "track" && (albumMode == "album" || x.flag("albumIntent"));  // §17.1
      if (const J* a = x.get("analysis"); a && a->t == J::T::Obj) {
        it.source = Item::Source::Full;
        it.track = trackOf(*a);
        GainContext c = ctx;
        c.decodeClipped = it.track->decodeClipped;
        const auto ag = inAlbum ? albumGain.find(key) : albumGain.end();
        if (inAlbum && ag != albumGain.end() && ag->second) {
          it.albumRun = true;
          it.baseDb = ag->second->gainDb;
          it.capDb = ag->second->capDb;
          it.rules = ag->second->rules;
        } else {
          const GainDecision d = decideTrackGain(it.track->r, it.track->u, c);
          it.baseDb = d.gainDb;
          it.capDb = d.capDb;
          it.rules = d.rules;
          if (inAlbum) it.rules.push_back({"ALBUM_PENDING", 0.0});
        }
      } else if (const J* j = x.get("jit"); j && j->t == J::T::Obj && j->num("jitLufs")) {  // §22.2
        it.source = Item::Source::Jit;
        const double g = ctx.targetLufs - *j->num("jitLufs");
        if (const auto tp = j->num("jitTpDbtp")) it.capDb = ctx.ceilingDbtp - (*tp + kJitTpMargin);
        it.baseDb = std::min(g, it.capDb);
        it.rules.push_back({"JIT", it.baseDb});
        if (it.capDb < g) it.rules.push_back({"PEAK_CAP", it.capDb - g});
      } else {  // §22.1
        it.baseDb = ctx.targetLufs + kProvisionalOffset;
        it.rules.push_back({"PROVISIONAL", it.baseDb});
      }
      items.push_back(std::move(it));
    }

  // §18 seams along the queue, in the engine domain (before learning and calibration). The chain follows what each
  // item actually plays with: its manual variant if it was started by the user, else its natural (seamed) variant.
  for (std::size_t i = 0; i < items.size(); ++i) {
    Item& cur = items[i];
    cur.naturalSeamedDb = cur.manualSeamedDb = cur.baseDb;
    if (cur.albumRun || cur.source != Item::Source::Full || i == 0) continue;
    const Item& prev = items[i - 1];
    SeamInput s;
    s.havePrev = true;
    s.prevSeamedDb = prev.manual ? prev.manualSeamedDb : prev.naturalSeamedDb;
    s.curTrackDb = cur.baseDb;
    s.curCapDb = cur.capDb;
    s.curOpenLufs = cur.track->u.openLufs;
    s.curPleOffset = cur.track->pleOffset();
    s.curFadeIn = cur.track->u.fadeIn.has_value();
    if (prev.track) {
      s.prevEndLufs = prev.track->u.endLufs;
      s.prevPleOffset = prev.track->pleOffset();
      s.prevFadeOut = prev.track->u.fadeOut.has_value();
    }
    cur.naturalSeamedDb = seamGain(s, &cur.rules);
  }

  std::string o = "{\"items\":[";
  for (std::size_t i = 0; i < items.size(); ++i) {
    const Item& it = items[i];
    const double gNat = finalGain(it.naturalSeamedDb, it.learnedDb, outputCal, it.capDb);  // §21
    const double gMan = finalGain(it.manualSeamedDb, it.learnedDb, outputCal, it.capDb);
    if (i) o += ',';
    o += "{\"gainDb\":" + num(gNat) + ",\"manualGainDb\":" + num(gMan) + ",\"baseDb\":" + num(it.baseDb) +
         ",\"learnedDb\":" + num(it.learnedDb) +
         ",\"capDb\":" + num(it.capDb) + ",\"source\":\"" +
         (it.source == Item::Source::Full ? "full" : it.source == Item::Source::Jit ? "jit" : "provisional") +
         "\",\"albumRun\":" + (it.albumRun ? "true" : "false") + ",\"rules\":" + rulesJson(it.rules) + "}";
  }
  return o + "]}";
}

std::string learnUpdateJson(const std::string& input) {  // §19.1 environment filter + §19.2 update
  J in;
  if (!Parser(input).parse(in) || in.t != J::T::Obj || !in.num("deltaDb")) return "{\"error\":\"bad input\"}";
  const double now = in.num("nowDays", 0.0), dv = *in.num("deltaDb");
  double omega = 1.0;
  if (const auto prev = in.num("prevDeltaDb"); prev && *prev != 0.0) {
    const double ratio = dv / *prev;
    if (ratio >= 0.5 && ratio <= 2.0) omega = 0.25;  // same sign, similar size: a change of environment
  }
  LearnedOffset t{in.num("trackDb", 0.0), in.num("trackDays", now)}, a{in.num("artistDb", 0.0), in.num("artistDays", now)};
  learnEvent(t, 0.5, omega * dv, now);   // eta_t
  learnEvent(a, 0.25, omega * dv, now);  // eta_a
  return "{\"trackDb\":" + num(t.valueDb) + ",\"trackDays\":" + num(t.updatedDays) + ",\"artistDb\":" + num(a.valueDb) +
         ",\"artistDays\":" + num(a.updatedDays) + ",\"omega\":" + num(omega) + "}";
}

}  // namespace sable
