// Queue planner (MATH §17.1, §18, §21, §22): exact checks of every path through planQueueJson.
#include <charconv>
#include <cmath>
#include <string>
#include <vector>

#include "doctest.h"
#include "sable/intent.h"
#include "sable/pipeline.h"
#include "sable/plan.h"
#include "signals.h"

namespace {

// Pulls the n-th occurrence of "key":<number> out of a JSON string (test helper only).
double field(const std::string& js, const std::string& key, int n = 0) {
  std::size_t p = 0;
  for (int i = 0; i <= n; ++i) {
    p = js.find("\"" + key + "\":", p);
    REQUIRE(p != std::string::npos);
    p += key.size() + 3;
  }
  double v = 0.0;
  std::from_chars(js.data() + p, js.data() + js.size(), v);
  return v;
}

std::string analysis(double li, double anchor, double ple, double tp, double open, double end, const char* character = "steady",
                     bool fadeIn = false, bool fadeOut = false) {
  auto n = [](double v) { return std::to_string(v); };
  return "{\"integratedLufs\":" + n(li) + ",\"truePeakDbtp\":" + n(tp) + ",\"shortFile\":false,\"decodeClipped\":false,\"anchorLufs\":" +
         n(anchor) + ",\"anchorPle\":" + n(ple) + ",\"anchorSec\":120,\"character\":\"" + character + "\",\"openLufs\":" + n(open) +
         ",\"endLufs\":" + n(end) + ",\"fadeIn\":" + (fadeIn ? "true" : "false") + ",\"fadeOut\":" + (fadeOut ? "true" : "false") +
         ",\"anchorBlockPower\":[" + n(std::pow(10.0, (anchor + 0.691) / 10.0)) + "]}";
}

std::string ctx(const std::string& items, const std::string& albums = "{}", const char* mode = "auto", double cal = 0.0) {
  return "{\"targetLufs\":-14,\"intimacyLu\":1,\"ceilingDbtp\":-1,\"limiter\":false,\"outputCalDb\":" + std::to_string(cal) +
         ",\"albumMode\":\"" + mode + "\",\"albums\":" + albums + ",\"items\":[" + items + "]}";
}

}  // namespace

TEST_CASE("plan: track gain equals the cached Standard decision of the full pipeline") {
  auto a = sigs::multiSine(44100, 2, 30.0, 60.0, 12000.0, 40, -20.0, 3);
  sable::TrackPipeline p(44100, 2, 0, false);
  p.pushPass1(a.x.data(), a.frames());
  if (p.endPass1()) do {
      p.beginPass2();
      p.pushPass2(a.x.data(), a.frames());
    } while (p.endPass2());
  const std::string js = p.resultJson();
  const std::string out = sable::planQueueJson(ctx("{\"analysis\":" + js + ",\"manual\":true}"));
  const double cached = field(js, "gainDb", 0);  // "standard":{"gainDb":..}
  CHECK(field(out, "gainDb") == cached);          // exact: same rules, exact JSON round trip
  CHECK(field(out, "manualGainDb") == cached);
}

TEST_CASE("plan: album world shares one gain; song world and pending albums use track mode") {
  const std::string t1 = analysis(-10, -10, -10, -0.5, -12, -12), t2 = analysis(-16, -16, -16, -6, -18, -18);
  const std::string albums = "{\"A\":{\"complete\":true,\"tracks\":[" + t1 + "," + t2 + "]}}";
  const std::string items = "{\"analysis\":" + t1 + ",\"album\":\"A\",\"albumIntent\":true},{\"analysis\":" + t2 +
                            ",\"album\":\"A\",\"albumIntent\":true}";
  const std::string out = sable::planQueueJson(ctx(items, albums));
  CHECK(field(out, "gainDb", 0) == field(out, "gainDb", 1));
  CHECK(out.find("\"albumRun\":true") != std::string::npos);
  // Album cap: the loudest member's TP (-0.5) bounds the shared gain to -0.5 dB.
  CHECK(field(out, "gainDb", 0) <= -0.5);

  // Song world (tapped): each track in its own world.
  const std::string song = "{\"analysis\":" + t1 + ",\"album\":\"A\",\"albumIntent\":false},{\"analysis\":" + t2 +
                           ",\"album\":\"A\",\"albumIntent\":false}";
  const std::string s = sable::planQueueJson(ctx(song, albums));
  CHECK(field(s, "baseDb", 0) == doctest::Approx(-4.0).epsilon(1e-12));
  CHECK(field(s, "baseDb", 1) == doctest::Approx(2.0).epsilon(1e-12));
  CHECK(s.find("\"albumRun\":true") == std::string::npos);

  // "Always album" setting overrides the intent; "always track" disables album gain.
  CHECK(sable::planQueueJson(ctx(song, albums, "album")).find("\"albumRun\":true") != std::string::npos);
  CHECK(sable::planQueueJson(ctx(items, albums, "track")).find("\"albumRun\":true") == std::string::npos);

  // Album not fully analysed: track mode + ALBUM_PENDING.
  const std::string pending = sable::planQueueJson(ctx(items, "{\"A\":{\"complete\":false,\"tracks\":[]}}"));
  CHECK(pending.find("ALBUM_PENDING") != std::string::npos);
  CHECK(field(pending, "baseDb", 0) == doctest::Approx(-4.0).epsilon(1e-12));
}

TEST_CASE("plan: seam smoothing stays within 0.5 dB, follows the applied chain, and is skipped for manual starts") {
  // prev: anchor -14 -> g 0; ends at -10. cur: anchor -14 -> g 0; opens at -20. Ideal = -10 + 0 - (-20) = +10 -> +0.5.
  const std::string prev = analysis(-14, -14, -14, -20, -14, -10), cur = analysis(-14, -14, -14, -20, -20, -14);
  const std::string out = sable::planQueueJson(ctx("{\"analysis\":" + prev + ",\"manual\":true},{\"analysis\":" + cur + "}"));
  CHECK(field(out, "gainDb", 1) == doctest::Approx(0.5).epsilon(1e-12));
  CHECK(field(out, "manualGainDb", 1) == doctest::Approx(0.0).epsilon(1e-12));
  CHECK(out.find("\"SEAM\"") != std::string::npos);
  // Fade-out on prev: no seam.
  const std::string faded = analysis(-14, -14, -14, -20, -14, -10, "steady", false, true);
  CHECK(field(sable::planQueueJson(ctx("{\"analysis\":" + faded + "},{\"analysis\":" + cur + "}")), "gainDb", 1) == 0.0);
  // A JIT item breaks the chain (no seam data).
  const std::string jitPrev = "{\"jit\":{\"jitLufs\":-10,\"jitTpDbtp\":-3}}";
  CHECK(field(sable::planQueueJson(ctx(jitPrev + ",{\"analysis\":" + cur + "}")), "gainDb", 1) == 0.0);
}

TEST_CASE("plan: JIT, provisional, calibration never breaks the cap") {
  const std::string out = sable::planQueueJson(ctx("{\"jit\":{\"jitLufs\":-8,\"jitTpDbtp\":-3}},{\"analysis\":null}"));
  CHECK(field(out, "gainDb", 0) == doctest::Approx(-6.0).epsilon(1e-12));  // min(-14+8, -1-(-3+1)) = min(-6, +1)
  CHECK(out.find("\"source\":\"jit\"") != std::string::npos);
  CHECK(field(out, "gainDb", 1) == doctest::Approx(-6.0).epsilon(1e-12));  // §22.1 T + 8
  CHECK(out.find("\"source\":\"provisional\"") != std::string::npos);
  // JIT peak cap: TP -0.5 + 1 margin -> cap -1.5.
  CHECK(field(sable::planQueueJson(ctx("{\"jit\":{\"jitLufs\":-20,\"jitTpDbtp\":-0.5}}")), "gainDb") == doctest::Approx(-1.5).epsilon(1e-12));
  // +3 dB output calibration on a track already at its cap: stays at the cap (§21 step 4).
  const std::string capped = analysis(-20, -20, -20, -2, -20, -20);  // intent +6, cap +1
  CHECK(field(sable::planQueueJson(ctx("{\"analysis\":" + capped + "}", "{}", "auto", 3.0)), "gainDb") == doctest::Approx(1.0).epsilon(1e-12));
}

TEST_CASE("plan: malformed input is rejected, output is deterministic") {
  CHECK(sable::planQueueJson("{\"items\":[") == "{\"error\":\"bad input\"}");
  CHECK(sable::planQueueJson("[]") == "{\"error\":\"bad input\"}");
  const std::string in = ctx("{\"jit\":{\"jitLufs\":-9.123456789,\"jitTpDbtp\":-2}}");
  CHECK(sable::planQueueJson(in) == sable::planQueueJson(in));
}

TEST_CASE("plan: blind A/B conditions are level-matched to Sable and respect the cap (MATH §30)") {
  // Three songs: anchors differ from integrated loudness, so plain -14 and Sable balance them differently.
  const std::string a = analysis(-8, -7, -7, -12, -8, -8), b = analysis(-20, -18, -18, -20, -20, -20), c = analysis(-12, -12, -12, -14, -12, -12);
  auto items = [&](const char*) {
    return "{\"analysis\":" + a + ",\"excerptSec\":25},{\"analysis\":" + b + ",\"excerptSec\":25},{\"analysis\":" + c + ",\"excerptSec\":25}";
  };
  auto run = [&](const char* cond) {
    std::string in = ctx(items(cond));
    in.insert(1, std::string("\"abCondition\":\"") + cond + "\",");
    return sable::planQueueJson(in);
  };
  const std::string s = run("sable"), p = run("plain"), o = run("off");
  const double lx[3] = {-7, -18, -12};
  auto seq = [&](const std::string& out) {
    double e = 0;
    for (int i = 0; i < 3; ++i) e += std::pow(10.0, (lx[i] + field(out, "gainDb", i)) / 10.0);
    return 10 * std::log10(e / 3);
  };
  // Sable = its manual (unseamed) gains: T - anchor = -7, +4, -2.
  CHECK(field(s, "gainDb", 0) == doctest::Approx(-7.0).epsilon(1e-12));
  CHECK(field(s, "gainDb", 1) == doctest::Approx(4.0).epsilon(1e-12));
  // Level match: every condition has Sable's sequence loudness (no cap active in this set).
  CHECK(seq(p) == doctest::Approx(seq(s)).epsilon(1e-9));
  CHECK(seq(o) == doctest::Approx(seq(s)).epsilon(1e-9));
  // Off is one constant gain; plain keeps its integrated-loudness differences (-6, +6, -2) up to the shift.
  CHECK(field(o, "gainDb", 0) == doctest::Approx(field(o, "gainDb", 1)).epsilon(1e-12));
  CHECK(field(p, "gainDb", 1) - field(p, "gainDb", 0) == doctest::Approx(12.0).epsilon(1e-9));
  CHECK(field(p, "manualGainDb", 2) == field(p, "gainDb", 2));

  // Cap: a hot song (TP -0.5) can never be pushed above C_out - TP by the level match.
  const std::string hot = analysis(-20, -20, -20, -0.5, -20, -20), quiet = analysis(-30, -30, -30, -20, -30, -30);
  std::string in = ctx("{\"analysis\":" + hot + ",\"excerptSec\":25},{\"analysis\":" + quiet + ",\"excerptSec\":25}");
  in.insert(1, "\"abCondition\":\"off\",");
  const std::string capped = sable::planQueueJson(in);
  CHECK(field(capped, "gainDb", 0) <= -0.5 + 1e-12);
}

TEST_CASE("plan: transitions round plays Sable as normal playback, seams and album world included (MATH §30.6)") {
  auto run = [](std::string items, const char* cond, bool seams, const std::string& albums = "{}") {
    std::string in = ctx(items, albums);
    in.insert(1, std::string("\"abCondition\":\"") + cond + "\"," + (seams ? "\"abSeams\":true," : ""));
    return sable::planQueueJson(in);
  };
  // Song change: same pair as the seam test; the second song opens 10 LU under the first song's ending.
  const std::string prev = analysis(-14, -14, -14, -20, -14, -10), cur = analysis(-14, -14, -14, -20, -20, -14);
  const std::string song = "{\"analysis\":" + prev + ",\"manual\":true,\"excerptSec\":20},{\"analysis\":" + cur + ",\"excerptSec\":20}";
  CHECK(field(run(song, "sable", true), "gainDb", 1) == doctest::Approx(0.5).epsilon(1e-12));   // seamed
  CHECK(field(run(song, "sable", false), "gainDb", 1) == doctest::Approx(0.0).epsilon(1e-12));  // §30.1: no seams
  CHECK(field(run(song, "sable", true), "gainDb", 0) == doctest::Approx(0.0).epsilon(1e-12));   // manual start
  // Album change: Sable shares the album gain, plain keeps the songs' own -6 dB difference.
  const std::string t1 = analysis(-10, -10, -10, -6, -12, -12), t2 = analysis(-16, -16, -16, -6, -18, -18);
  const std::string albums = "{\"A\":{\"complete\":true,\"tracks\":[" + t1 + "," + t2 + "]}}";
  const std::string alb = "{\"analysis\":" + t1 + ",\"album\":\"A\",\"albumIntent\":true,\"manual\":true,\"excerptSec\":20},"
                          "{\"analysis\":" + t2 + ",\"album\":\"A\",\"albumIntent\":true,\"excerptSec\":20}";
  const std::string s = run(alb, "sable", true, albums), p = run(alb, "plain", true, albums);
  CHECK(field(s, "gainDb", 0) == field(s, "gainDb", 1));
  CHECK(field(p, "gainDb", 1) - field(p, "gainDb", 0) == doctest::Approx(6.0).epsilon(1e-9));
  // Level match still holds (anchors -10, -16).
  auto seq = [](const std::string& out) {
    return 10 * std::log10((std::pow(10.0, (-10 + field(out, "gainDb", 0)) / 10.0) + std::pow(10.0, (-16 + field(out, "gainDb", 1)) / 10.0)) / 2);
  };
  CHECK(seq(p) == doctest::Approx(seq(s)).epsilon(1e-9));
}

TEST_CASE("plan: condition L is Sable without the PLE offset, level-matched to Sable (MATH §30.7)") {
  // a feels 3 LU louder than its LUFS (PLE -11 vs anchor -14); b has no offset.
  const std::string a = analysis(-14, -14, -11, -20, -14, -14), b = analysis(-14, -14, -14, -20, -14, -14);
  const std::string items = "{\"analysis\":" + a + ",\"excerptSec\":20},{\"analysis\":" + b + ",\"excerptSec\":20}";
  auto run = [&](const char* cond) {
    std::string in = ctx(items);
    in.insert(1, std::string("\"abCondition\":\"") + cond + "\",");
    return sable::planQueueJson(in);
  };
  const std::string s = run("sable"), l = run("sablelufs");
  CHECK(field(s, "gainDb", 1) - field(s, "gainDb", 0) == doctest::Approx(3.0).epsilon(1e-9));  // Sable follows the PLE
  CHECK(field(l, "gainDb", 1) - field(l, "gainDb", 0) == doctest::Approx(0.0).epsilon(1e-9));  // L follows LUFS only
  auto seq = [](const std::string& out) {
    return 10 * std::log10((std::pow(10.0, (-14 + field(out, "gainDb", 0)) / 10.0) + std::pow(10.0, (-14 + field(out, "gainDb", 1)) / 10.0)) / 2);
  };
  CHECK(seq(l) == doctest::Approx(seq(s)).epsilon(1e-9));  // matched to real Sable's loudness
}

TEST_CASE("plan: contour headroom lowers the cap; learned offsets decay and stay bounded (MATH §19, §24.4)") {
  const std::string capped = analysis(-20, -20, -20, -2, -20, -20);  // intent +6, cap +1
  std::string in = ctx("{\"analysis\":" + capped + "}");
  in.insert(1, "\"contourHeadroomDb\":2.6,");
  CHECK(field(sable::planQueueJson(in), "gainDb") == doctest::Approx(1.0 - 2.6).epsilon(1e-12));

  const std::string mid = analysis(-14, -14, -14, -20, -14, -14);  // g 0, cap +19
  // Track offset -1.6 set 30 days ago -> -0.8 now; artist -1 (1 day old) ignored until 2 tracks have taught it.
  std::string l = ctx("{\"analysis\":" + mid + ",\"learn\":{\"trackDb\":-1.6,\"trackDays\":0,\"artistDb\":-1,\"artistDays\":29,\"artistTracks\":1}}");
  l.insert(1, "\"nowDays\":30,");
  const std::string out = sable::planQueueJson(l);
  CHECK(field(out, "learnedDb") == doctest::Approx(-0.8).epsilon(1e-9));
  CHECK(field(out, "gainDb") == doctest::Approx(-0.8).epsilon(1e-9));
  std::string l2 = l;
  l2.replace(l2.find("\"artistTracks\":1"), 16, "\"artistTracks\":2");
  CHECK(field(sable::planQueueJson(l2), "learnedDb") == doctest::Approx(-0.8 - std::pow(2.0, -1.0 / 30.0)).epsilon(1e-9));
}

TEST_CASE("learn: event update, environment filter and clamp (MATH §19.1-19.2)") {
  const std::string a = sable::learnUpdateJson("{\"deltaDb\":-3,\"trackDb\":0,\"trackDays\":10,\"artistDb\":0,\"artistDays\":10,\"nowDays\":10}");
  CHECK(field(a, "trackDb") == doctest::Approx(-1.5));
  CHECK(field(a, "artistDb") == doctest::Approx(-0.75));
  CHECK(field(a, "omega") == 1.0);
  // Same sign and similar size as the previous event: weighted 0.25.
  const std::string b = sable::learnUpdateJson("{\"deltaDb\":-3,\"prevDeltaDb\":-4,\"trackDb\":0,\"trackDays\":10,\"artistDb\":0,\"artistDays\":10,\"nowDays\":10}");
  CHECK(field(b, "omega") == 0.25);
  CHECK(field(b, "trackDb") == doctest::Approx(-0.375));
  // Opposite sign: full weight. Clamp at +-2.
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":3,\"prevDeltaDb\":-3,\"nowDays\":0}"), "omega") == 1.0);
  CHECK(field(sable::learnUpdateJson("{\"deltaDb\":-12,\"trackDb\":-1.9,\"trackDays\":5,\"nowDays\":5}"), "trackDb") == -2.0);
  CHECK(sable::learnUpdateJson("{}") == "{\"error\":\"bad input\"}");
}
