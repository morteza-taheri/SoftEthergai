import os
import sys

# Allow `import vpngate_collector` from the repository root.
sys.path.insert(
    0,
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
)
