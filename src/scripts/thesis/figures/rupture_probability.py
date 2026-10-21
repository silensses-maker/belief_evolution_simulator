"""Figure: continuous rupture probability g (thesis eq:decaimiento) vs. the hard threshold of classic bounded confidence.

(a) g(d) = 1 - exp(1 - d/tau) for d > tau, 0 otherwise, against the step rule (0 below tau, 1 above), for
    tau = 0.10 (all thesis experiments) and tau = 0.20.
(b) effective per-round rupture probability P_break * g used by the simulator, for P_break = 0.2 (benchmark)
    and P_break = 1 (case studies, Case 2B), tau = 0.10.

usage: python -X utf8 generate_rupture_figure.py <out_dir> [<out_dir2> ...]   (writes fig_ruptura_continua.pdf/.png)
"""
import os, sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')
d = np.linspace(0, 1, 2001)


def g(d, tau):
    return np.where(d > tau, 1 - np.exp(1 - d / tau), 0.0)


def step(d, tau):
    return np.where(d > tau, 1.0, 0.0)


fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5), dpi=300)
colors = {0.10: '#2b5c8f', 0.20: '#d35400'}

for tau in (0.10, 0.20):
    c = colors[tau]
    ax1.plot(d, step(d, tau), color=c, lw=1.6, ls='--', alpha=0.8,
             label=fr'Umbral escalón, $\tau = {tau:.2f}$')
    ax1.plot(d, g(d, tau), color=c, lw=2.6, label=fr'Ruptura continua $g$, $\tau = {tau:.2f}$')
    ax1.axvline(tau, color=c, lw=0.8, ls=':', alpha=0.7)
    g1 = 1 - np.exp(1 - 1 / tau)
    ax1.plot([1], [g1], 'o', color=c, ms=6)
    ax1.annotate(fr'$g(1) = 1 - e^{{1 - 1/{tau:.2f}}} \approx {g1:.4f}$', xy=(1, g1),
                 xytext=(0.50, 0.36 if tau == 0.20 else 0.48), textcoords='data', fontsize=9.5,
                 arrowprops=dict(arrowstyle='->', color=c, lw=1.2), color=c)
ax1.set_title(r'(a) Probabilidad de ruptura $g_{ji}^t$ frente a un umbral escalón', fontsize=12, fontweight='bold')
ax1.set_xlabel(r'Distancia de opinión percibida $\hat{d}_{ij}^t$', fontsize=11, fontweight='bold')
ax1.set_ylabel(r'Probabilidad de ruptura', fontsize=11, fontweight='bold')
ax1.set_xlim(0, 1); ax1.set_ylim(-0.02, 1.08)
ax1.legend(loc='lower right', fontsize=9, frameon=True)
ax1.grid(True, linestyle='--', alpha=0.6)

tau = 0.10
for pb, c, lab in ((1.0, '#8e44ad', r'$P_{\mathrm{break}} = 1$ (casos de estudio, Caso 2B)'),
                   (0.2, '#16a085', r'$P_{\mathrm{break}} = 0.2$ (benchmark)')):
    ax2.plot(d, pb * g(d, tau), color=c, lw=2.6, label=lab)
ax2.axvline(tau, color='gray', lw=0.8, ls=':')
ax2.text(tau + 0.01, 1.0, r'$\tau = 0.10$', fontsize=9.5, color='gray', va='top')
ax2.set_title(r'(b) Probabilidad efectiva por ronda $P_{\mathrm{break}} \cdot g_{ji}^t$ ($\tau = 0.10$)',
              fontsize=12, fontweight='bold')
ax2.set_xlabel(r'Distancia de opinión percibida $\hat{d}_{ij}^t$', fontsize=11, fontweight='bold')
ax2.set_ylabel(r'Probabilidad de ruptura por ronda', fontsize=11, fontweight='bold')
ax2.set_xlim(0, 1); ax2.set_ylim(-0.02, 1.08)
ax2.legend(loc='center right', fontsize=9, frameon=True)
ax2.grid(True, linestyle='--', alpha=0.6)

plt.tight_layout()
for out in sys.argv[1:]:
    for ext in ('pdf', 'png'):
        p = os.path.join(out, f'fig_ruptura_continua.{ext}')
        fig.savefig(p, bbox_inches='tight')
        print('wrote', p)
