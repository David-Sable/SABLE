% MATLAB-compatible symmetric Hann window (Octave's signal package may be absent). Test shim.
function w = hann(n)
  if n == 1, w = 1; return; end
  w = 0.5 - 0.5*cos(2*pi*(0:n-1)'/(n-1));
end
